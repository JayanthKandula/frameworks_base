/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm.domain.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageUserState;
import android.content.pm.domain.verify.DomainVerificationManager;
import android.content.pm.domain.verify.DomainVerificationSet;
import android.content.pm.domain.verify.DomainVerificationState;
import android.content.pm.domain.verify.DomainVerificationUserSelection;
import android.content.pm.domain.verify.IDomainVerificationManager;
import android.os.Binder;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Singleton;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.CollectionUtils;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.domain.verify.models.DomainVerificationPkgState;
import com.android.server.pm.domain.verify.models.DomainVerificationStateMap;
import com.android.server.pm.domain.verify.models.DomainVerificationUserState;
import com.android.server.pm.domain.verify.proxy.DomainVerificationProxy;
import com.android.server.pm.domain.verify.proxy.DomainVerificationProxyUnavailable;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DomainVerificationService extends SystemService
        implements DomainVerificationManagerInternal, DomainVerificationShell.Callback {

    private static final String TAG = "DomainVerificationService";

    /**
     * States that are currently alive and attached to a package. Entries are exclusive with the
     * state stored in {@link DomainVerificationSettings}, as any pending/restored state should be
     * immediately attached once its available.
     * <p>
     * Generally this should be not accessed directly. Prefer calling {@link
     * #getAndValidateAttachedLocked(UUID, Set, boolean)}.
     *
     * @see #getAndValidateAttachedLocked(UUID, Set, boolean)
     **/
    @GuardedBy("mLock")
    @NonNull
    private final DomainVerificationStateMap<DomainVerificationPkgState> mAttachedPkgStates =
            new DomainVerificationStateMap<>();

    /**
     * Lock for all state reads/writes.
     */
    private final Object mLock = new Object();

    @NonNull
    private final Singleton<Connection> mConnection;

    @NonNull
    private final SystemConfig mSystemConfig;

    @NonNull
    private final DomainVerificationSettings mSettings;

    @NonNull
    private final DomainVerificationCollector mCollector;

    @NonNull
    private final DomainVerificationEnforcer mEnforcer;

    @NonNull
    private final DomainVerificationDebug mDebug;

    @NonNull
    private final DomainVerificationShell mShell;

    @NonNull
    private final IDomainVerificationManager.Stub mStub = new DomainVerificationManagerStub(this);

    @NonNull
    private DomainVerificationProxy mProxy = new DomainVerificationProxyUnavailable();

    public DomainVerificationService(@NonNull Context context, @NonNull SystemConfig systemConfig,
            @NonNull PlatformCompat platformCompat, @NonNull Singleton<Connection> connection) {
        super(context);
        mConnection = connection;
        mSystemConfig = systemConfig;
        mSettings = new DomainVerificationSettings();
        mCollector = new DomainVerificationCollector(platformCompat, systemConfig);
        mEnforcer = new DomainVerificationEnforcer(context);
        mDebug = new DomainVerificationDebug(mCollector);
        mShell = new DomainVerificationShell(this);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DOMAIN_VERIFICATION_SERVICE, mStub);
    }

    @NonNull
    @Override
    public DomainVerificationProxy getProxy() {
        return mProxy;
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase != SystemService.PHASE_BOOT_COMPLETED || !hasRealVerifier()) {
            return;
        }

        verifyPackages(null, false);
    }

    @Override
    public void setProxy(@NonNull DomainVerificationProxy proxy) {
        mProxy = proxy;
    }

    @NonNull
    @Override
    public List<String> getValidVerificationPackageNames() {
        mEnforcer.assertApprovedVerifier(mConnection.get().getCallingUid(), mProxy);
        List<String> packageNames = new ArrayList<>();
        synchronized (mLock) {
            int size = mAttachedPkgStates.size();
            for (int index = 0; index < size; index++) {
                DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(index);
                if (pkgState.isHasAutoVerifyDomains()) {
                    packageNames.add(pkgState.getPackageName());
                }
            }
        }
        return packageNames;
    }

    @Nullable
    @Override
    public DomainVerificationSet getDomainVerificationSet(@NonNull String packageName)
            throws NameNotFoundException {
        mEnforcer.assertApprovedQuerent(mConnection.get().getCallingUid(), mProxy);
        synchronized (mLock) {
            DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
            if (pkgState == null) {
                return null;
            }

            AndroidPackage pkg = mConnection.get().getPackageLocked(packageName);
            if (pkg == null) {
                throw DomainVerificationUtils.throwPackageUnavailable(packageName);
            }

            Map<String, Integer> hostToStateMap = new ArrayMap<>(pkgState.getStateMap());

            // TODO(b/159952358): Should the domain list be cached?
            ArraySet<String> domains = mCollector.collectAutoVerifyDomains(pkg);
            if (domains.isEmpty()) {
                return null;
            }

            int size = domains.size();
            for (int index = 0; index < size; index++) {
                hostToStateMap.putIfAbsent(domains.valueAt(index),
                        DomainVerificationState.STATE_NO_RESPONSE);
            }

            // TODO(b/159952358): Do not return if no values are editable (all ignored states)?
            return new DomainVerificationSet(pkgState.getId(), packageName, hostToStateMap);
        }
    }

    @Override
    public void setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            int state) throws InvalidDomainSetException, NameNotFoundException {
        mEnforcer.assertApprovedVerifier(mConnection.get().getCallingUid(), mProxy);
        if (state < DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED) {
            if (state != DomainVerificationState.STATE_SUCCESS) {
                throw new IllegalArgumentException(
                        "Verifier can only set STATE_SUCCESS or codes greater than or equal to "
                                + "STATE_FIRST_VERIFIER_DEFINED");
            }
        }

        synchronized (mLock) {
            DomainVerificationPkgState pkgState = getAndValidateAttachedLocked(domainSetId, domains,
                    true /* forAutoVerify */);
            ArrayMap<String, Integer> stateMap = pkgState.getStateMap();
            for (String domain : domains) {
                Integer previousState = stateMap.get(domain);
                if (previousState != null
                        && !DomainVerificationManager.isStateModifiable(previousState)) {
                    continue;
                }

                stateMap.put(domain, state);
            }
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationStatusInternal(@Nullable String packageName, int state,
            @Nullable ArraySet<String> domains) throws NameNotFoundException {
        mEnforcer.assertInternal(mConnection.get().getCallingUid());

        switch (state) {
            case DomainVerificationState.STATE_NO_RESPONSE:
            case DomainVerificationState.STATE_SUCCESS:
            case DomainVerificationState.STATE_APPROVED:
            case DomainVerificationState.STATE_DENIED:
                break;
            default:
                throw new IllegalArgumentException(
                        "State must be one of NO_RESPONSE, SUCCESS, APPROVED, or DENIED");
        }

        if (packageName == null) {
            synchronized (mLock) {
                ArraySet<String> validDomains = new ArraySet<>();

                int size = mAttachedPkgStates.size();
                for (int index = 0; index < size; index++) {
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(index);
                    String pkgName = pkgState.getPackageName();
                    PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(pkgName);
                    if (pkgSetting == null || pkgSetting.getPkg() == null) {
                        continue;
                    }

                    AndroidPackage pkg = pkgSetting.getPkg();

                    validDomains.clear();

                    ArraySet<String> autoVerifyDomains = mCollector.collectAutoVerifyDomains(pkg);
                    if (domains == null) {
                        validDomains.addAll(autoVerifyDomains);
                    } else {
                        validDomains.addAll(domains);
                        validDomains.retainAll(autoVerifyDomains);
                    }

                    setDomainVerificationStatusInternal(pkgState, state, validDomains);
                }
            }
        } else {
            synchronized (mLock) {
                DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
                if (pkgState == null) {
                    throw DomainVerificationUtils.throwPackageUnavailable(packageName);
                }

                PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(packageName);
                if (pkgSetting == null || pkgSetting.getPkg() == null) {
                    throw DomainVerificationUtils.throwPackageUnavailable(packageName);
                }

                AndroidPackage pkg = pkgSetting.getPkg();
                if (domains == null) {
                    domains = mCollector.collectAutoVerifyDomains(pkg);
                } else {
                    domains.retainAll(mCollector.collectAutoVerifyDomains(pkg));
                }

                setDomainVerificationStatusInternal(pkgState, state, domains);
            }
        }

        mConnection.get().scheduleWriteSettings();
    }

    private void setDomainVerificationStatusInternal(@NonNull DomainVerificationPkgState pkgState,
            int state, @NonNull ArraySet<String> validDomains) {
        ArrayMap<String, Integer> stateMap = pkgState.getStateMap();
        int size = validDomains.size();
        for (int index = 0; index < size; index++) {
            stateMap.put(validDomains.valueAt(index), state);
        }
    }

    @Override
    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed) throws NameNotFoundException {
        setDomainVerificationLinkHandlingAllowed(packageName, allowed,
                mConnection.get().getCallingUserId());
    }

    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed, @UserIdInt int userId) throws NameNotFoundException {
        Connection connection = mConnection.get();
        mEnforcer.assertApprovedUserSelector(connection.getCallingUid(),
                connection.getCallingUserId(), userId);
        synchronized (mLock) {
            DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
            if (pkgState == null) {
                throw DomainVerificationUtils.throwPackageUnavailable(packageName);
            }

            pkgState.getOrCreateUserSelectionState(userId)
                    .setDisallowLinkHandling(!allowed);
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationLinkHandlingAllowedInternal(@Nullable String packageName,
            boolean allowed, @UserIdInt int userId) throws NameNotFoundException {
        mEnforcer.assertInternal(mConnection.get().getCallingUid());
        if (packageName == null) {
            synchronized (mLock) {
                int pkgStateSize = mAttachedPkgStates.size();
                for (int pkgStateIndex = 0; pkgStateIndex < pkgStateSize; pkgStateIndex++) {
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(pkgStateIndex);
                    if (userId == UserHandle.USER_ALL) {
                        SparseArray<DomainVerificationUserState> userStates =
                                pkgState.getUserSelectionStates();
                        int userStatesSize = userStates.size();
                        for (int userStateIndex = 0; userStateIndex < userStatesSize;
                                userStateIndex++) {
                            userStates.valueAt(userStateIndex)
                                    .setDisallowLinkHandling(!allowed);
                        }
                    } else {
                        pkgState.getOrCreateUserSelectionState(userId)
                                .setDisallowLinkHandling(!allowed);
                    }
                }

            }
        } else {
            synchronized (mLock) {
                DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
                if (pkgState == null) {
                    throw DomainVerificationUtils.throwPackageUnavailable(packageName);
                }

                pkgState.getOrCreateUserSelectionState(userId)
                        .setDisallowLinkHandling(!allowed);
            }
        }
    }

    @Override
    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled)
            throws InvalidDomainSetException, NameNotFoundException {
        setDomainVerificationUserSelection(domainSetId, domains, enabled,
                mConnection.get().getCallingUserId());
    }

    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled, @UserIdInt int userId)
            throws InvalidDomainSetException, NameNotFoundException {
        Connection connection = mConnection.get();
        mEnforcer.assertApprovedUserSelector(connection.getCallingUid(),
                connection.getCallingUserId(), userId);
        synchronized (mLock) {
            DomainVerificationPkgState pkgState = getAndValidateAttachedLocked(domainSetId, domains,
                    false /* forAutoVerify */);
            DomainVerificationUserState userState = pkgState.getOrCreateUserSelectionState(userId);
            if (enabled) {
                userState.addHosts(domains);
            } else {
                userState.removeHosts(domains);
            }
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationUserSelectionInternal(@UserIdInt int userId,
            @Nullable String packageName, boolean enabled, @NonNull ArraySet<String> domains)
            throws NameNotFoundException {
        mEnforcer.assertInternal(mConnection.get().getCallingUid());

        if (packageName == null) {
            synchronized (mLock) {
                Set<String> validDomains = new ArraySet<>();

                int size = mAttachedPkgStates.size();
                for (int index = 0; index < size; index++) {
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(index);
                    String pkgName = pkgState.getPackageName();
                    PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(pkgName);
                    if (pkgSetting == null || pkgSetting.getPkg() == null) {
                        continue;
                    }

                    validDomains.clear();
                    validDomains.addAll(domains);

                    setDomainVerificationUserSelectionInternal(userId, pkgState,
                            pkgSetting.getPkg(), enabled, validDomains);
                }
            }
        } else {
            synchronized (mLock) {
                DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
                if (pkgState == null) {
                    throw DomainVerificationUtils.throwPackageUnavailable(packageName);
                }

                PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(packageName);
                if (pkgSetting == null || pkgSetting.getPkg() == null) {
                    throw DomainVerificationUtils.throwPackageUnavailable(packageName);
                }

                setDomainVerificationUserSelectionInternal(userId, pkgState, pkgSetting.getPkg(),
                        enabled, domains);
            }
        }
    }

    private void setDomainVerificationUserSelectionInternal(int userId,
            @NonNull DomainVerificationPkgState pkgState, @NonNull AndroidPackage pkg,
            boolean enabled, Set<String> domains) {
        domains.retainAll(mCollector.collectAllWebDomains(pkg));

        SparseArray<DomainVerificationUserState> userStates =
                pkgState.getUserSelectionStates();
        if (userId == UserHandle.USER_ALL) {
            int size = userStates.size();
            for (int index = 0; index < size; index++) {
                DomainVerificationUserState userState = userStates.valueAt(index);
                if (enabled) {
                    userState.addHosts(domains);
                } else {
                    userState.removeHosts(domains);
                }
            }
        } else {
            DomainVerificationUserState userState = pkgState.getOrCreateUserSelectionState(userId);
            if (enabled) {
                userState.addHosts(domains);
            } else {
                userState.removeHosts(domains);
            }
        }
    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName) throws NameNotFoundException {
        return getDomainVerificationUserSelection(packageName,
                mConnection.get().getCallingUserId());
    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName, @UserIdInt int userId) throws NameNotFoundException {
        Connection connection = mConnection.get();
        mEnforcer.assertApprovedUserSelector(connection.getCallingUid(),
                connection.getCallingUserId(), userId);
        synchronized (mLock) {
            DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
            if (pkgState == null) {
                return null;
            }

            AndroidPackage pkg = connection.getPackageLocked(packageName);
            if (pkg == null) {
                throw DomainVerificationUtils.throwPackageUnavailable(packageName);
            }

            ArrayMap<String, Boolean> hostToUserSelectionMap = new ArrayMap<>();

            ArraySet<String> domains = mCollector.collectAllWebDomains(pkg);
            int domainsSize = domains.size();
            for (int index = 0; index < domainsSize; index++) {
                hostToUserSelectionMap.put(domains.valueAt(index), false);
            }

            boolean openVerifiedLinks = false;
            DomainVerificationUserState userState = pkgState.getUserSelectionState(userId);
            if (userState != null) {
                openVerifiedLinks = !userState.isDisallowLinkHandling();
                ArraySet<String> enabledHosts = userState.getEnabledHosts();
                int hostsSize = enabledHosts.size();
                for (int index = 0; index < hostsSize; index++) {
                    hostToUserSelectionMap.put(enabledHosts.valueAt(index), true);
                }
            }

            return new DomainVerificationUserSelection(pkgState.getId(), packageName,
                    UserHandle.of(userId), openVerifiedLinks, hostToUserSelectionMap);
        }
    }

    @NonNull
    @Override
    public UUID generateNewId() {
        // TODO(b/159952358): Domain set ID collisions
        return UUID.randomUUID();
    }

    @Override
    public void migrateState(@NonNull PackageSetting oldPkgSetting,
            @NonNull PackageSetting newPkgSetting) {
        String pkgName = newPkgSetting.name;
        boolean sendBroadcast;

        synchronized (mLock) {
            UUID oldDomainSetId = oldPkgSetting.getDomainSetId();
            UUID newDomainSetId = newPkgSetting.getDomainSetId();
            DomainVerificationPkgState oldPkgState = mAttachedPkgStates.remove(oldDomainSetId);

            AndroidPackage oldPkg = oldPkgSetting.getPkg();
            AndroidPackage newPkg = newPkgSetting.getPkg();

            ArrayMap<String, Integer> newStateMap = new ArrayMap<>();
            SparseArray<DomainVerificationUserState> newUserStates = new SparseArray<>();

            if (oldPkgState == null || oldPkg == null || newPkg == null) {
                // Should be impossible, but to be safe, continue with a new blank state instead
                Slog.wtf(TAG, "Invalid state nullability old state = " + oldPkgState
                        + ", old pkgSetting = " + oldPkgSetting
                        + ", new pkgSetting = " + newPkgSetting
                        + ", old pkg = " + oldPkg
                        + ", new pkg = " + newPkg, new Exception());

                DomainVerificationPkgState newPkgState = new DomainVerificationPkgState(
                        pkgName, newDomainSetId, true, newStateMap, newUserStates);
                mAttachedPkgStates.put(pkgName, newDomainSetId, newPkgState);
                return;
            }

            ArrayMap<String, Integer> oldStateMap = oldPkgState.getStateMap();
            ArraySet<String> newAutoVerifyDomains = mCollector.collectAutoVerifyDomains(newPkg);
            int newDomainsSize = newAutoVerifyDomains.size();

            for (int newDomainsIndex = 0; newDomainsIndex < newDomainsSize; newDomainsIndex++) {
                String domain = newAutoVerifyDomains.valueAt(newDomainsIndex);
                Integer oldStateInteger = oldStateMap.get(domain);
                if (oldStateInteger != null) {
                    int oldState = oldStateInteger;
                    switch (oldState) {
                        case DomainVerificationState.STATE_SUCCESS:
                        case DomainVerificationState.STATE_RESTORED:
                        case DomainVerificationState.STATE_MIGRATED:
                            newStateMap.put(domain, oldState);
                            break;
                        default:
                            // In all other cases, the state code is left unset
                            // (STATE_NO_RESPONSE) to signal to the verification agent that any
                            // existing error has been cleared and the domain should be
                            // re-attempted. This makes update of a package a signal to
                            // re-verify.
                            break;
                    }
                }
            }

            SparseArray<DomainVerificationUserState> oldUserStates =
                    oldPkgState.getUserSelectionStates();
            int oldUserStatesSize = oldUserStates.size();
            if (oldUserStatesSize > 0) {
                ArraySet<String> newWebDomains = mCollector.collectAutoVerifyDomains(newPkg);
                for (int oldUserStatesIndex = 0; oldUserStatesIndex < oldUserStatesSize;
                        oldUserStatesIndex++) {
                    int userId = oldUserStates.keyAt(oldUserStatesIndex);
                    DomainVerificationUserState oldUserState = oldUserStates.valueAt(
                            oldUserStatesIndex);
                    ArraySet<String> oldEnabledHosts = oldUserState.getEnabledHosts();
                    ArraySet<String> newEnabledHosts = new ArraySet<>(oldEnabledHosts);
                    newEnabledHosts.retainAll(newWebDomains);
                    DomainVerificationUserState newUserState = new DomainVerificationUserState(
                            userId, newEnabledHosts, oldUserState.isDisallowLinkHandling());
                    newUserStates.put(userId, newUserState);
                }
            }

            boolean hasAutoVerifyDomains = newDomainsSize > 0;
            boolean stateApplied = applyImmutableState(pkgName, newStateMap, newAutoVerifyDomains);

            // TODO(b/159952358): sendBroadcast should be abstracted so it doesn't have to be aware
            //  of whether/what state was applied. Probably some method which iterates the map to
            //  check for any domains that actually have state changeable by the domain verification
            //  agent.
            sendBroadcast = hasAutoVerifyDomains && !stateApplied;

            mAttachedPkgStates.put(pkgName, newDomainSetId, new DomainVerificationPkgState(
                    pkgName, newDomainSetId, hasAutoVerifyDomains, newStateMap, newUserStates));
        }

        if (sendBroadcast) {
            sendBroadcastForPackage(pkgName);
        }
    }

    // TODO(b/159952358): Handle valid domainSetIds for PackageSettings with no AndroidPackage
    @Override
    public void addPackage(@NonNull PackageSetting newPkgSetting) {
        // TODO(b/159952358): Optimize packages without any domains. Those wouldn't have to be in
        //  the state map, but it would require handling the "migration" case where an app either
        //  gains or loses all domains.

        UUID domainSetId = newPkgSetting.getDomainSetId();
        String pkgName = newPkgSetting.name;

        boolean sendBroadcast = true;

        DomainVerificationPkgState pkgState;
        pkgState = mSettings.getPendingState(pkgName);
        if (pkgState != null) {
            // Don't send when attaching from pending read, which is usually boot scan. Re-send on
            // boot is handled in a separate method once all packages are added.
            sendBroadcast = false;
        } else {
            pkgState = mSettings.getRestoredState(pkgName);
        }

        AndroidPackage pkg = newPkgSetting.getPkg();
        ArraySet<String> domains = mCollector.collectAutoVerifyDomains(pkg);
        boolean hasAutoVerifyDomains = !domains.isEmpty();
        boolean isPendingOrRestored = pkgState != null;
        if (isPendingOrRestored) {
            pkgState.setId(domainSetId);
        } else {
            pkgState = new DomainVerificationPkgState(pkgName, domainSetId, hasAutoVerifyDomains);
        }

        boolean stateApplied = applyImmutableState(pkgState, domains);
        if (!stateApplied && !isPendingOrRestored) {
            // TODO(b/159952358): Test this behavior
            // Attempt to preserve user experience by automatically verifying all domains from
            // legacy state if they were previously approved, or by automatically enabling all
            // hosts through user selection if legacy state indicates a user previously made the
            // choice in settings to allow supported links. The domain verification agent should
            // re-verify these links (set to STATE_MIGRATED) at the next possible opportunity,
            // and disable them if appropriate.
            ArraySet<String> webDomains = null;

            @SuppressWarnings("deprecation")
            SparseArray<PackageUserState> userState = newPkgSetting.getUserState();
            int userStateSize = userState.size();
            for (int index = 0; index < userStateSize; index++) {
                int userId = userState.keyAt(index);
                int legacyStatus = userState.valueAt(index).domainVerificationStatus;
                if (legacyStatus
                        == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
                    if (webDomains == null) {
                        webDomains = mCollector.collectAllWebDomains(pkg);
                    }

                    pkgState.getOrCreateUserSelectionState(userId).addHosts(webDomains);
                }
            }

            IntentFilterVerificationInfo legacyInfo =
                    newPkgSetting.getIntentFilterVerificationInfo();
            if (legacyInfo != null
                    && legacyInfo.getStatus()
                    == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
                ArrayMap<String, Integer> stateMap = pkgState.getStateMap();
                int domainsSize = domains.size();
                for (int index = 0; index < domainsSize; index++) {
                    stateMap.put(domains.valueAt(index), DomainVerificationState.STATE_MIGRATED);
                }
            }
        }

        synchronized (mLock) {
            mAttachedPkgStates.put(pkgName, domainSetId, pkgState);
        }

        if (sendBroadcast && hasAutoVerifyDomains) {
            sendBroadcastForPackage(pkgName);
        }
    }

    private boolean applyImmutableState(@NonNull DomainVerificationPkgState pkgState,
            @NonNull ArraySet<String> autoVerifyDomains) {
        return applyImmutableState(pkgState.getPackageName(), pkgState.getStateMap(),
                autoVerifyDomains);
    }

    /**
     * Applies any immutable state as the final step when adding or migrating state. Currently only
     * applies {@link SystemConfig#getLinkedApps()}, which approves all domains for a package.
     */
    private boolean applyImmutableState(@NonNull String packageName,
            @NonNull ArrayMap<String, Integer> stateMap,
            @NonNull ArraySet<String> autoVerifyDomains) {
        if (mSystemConfig.getLinkedApps().contains(packageName)) {
            int domainsSize = autoVerifyDomains.size();
            for (int index = 0; index < domainsSize; index++) {
                stateMap.put(autoVerifyDomains.valueAt(index),
                        DomainVerificationState.STATE_APPROVED);
            }
            return true;
        }

        return false;
    }

    @Override
    public void writeSettings(@NonNull TypedXmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            mSettings.writeSettings(serializer, mAttachedPkgStates);
        }
    }

    @Override
    public void readSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            mSettings.readSettings(parser, mAttachedPkgStates);
        }
    }

    @Override
    public void restoreSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            mSettings.restoreSettings(parser, mAttachedPkgStates);
        }
    }

    @Override
    public void clearPackage(@NonNull String packageName) {
        synchronized (mLock) {
            mAttachedPkgStates.remove(packageName);
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void clearUser(@UserIdInt int userId) {
        synchronized (mLock) {
            int attachedSize = mAttachedPkgStates.size();
            for (int index = 0; index < attachedSize; index++) {
                mAttachedPkgStates.valueAt(index).removeUser(userId);
            }

            mSettings.removeUser(userId);
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public boolean runMessage(int messageCode, Object object) {
        return mProxy.runMessage(messageCode, object);
    }

    @Override
    public void printState(@NonNull IndentingPrintWriter writer, @Nullable String packageName,
            @Nullable @UserIdInt Integer userId) throws NameNotFoundException {
        synchronized (mLock) {
            mDebug.printState(writer, packageName, userId, mConnection.get(), mAttachedPkgStates);
        }
    }

    @NonNull
    @Override
    public DomainVerificationShell getShell() {
        return mShell;
    }

    private void sendBroadcastForPackage(@NonNull String packageName) {
        mProxy.sendBroadcastForPackages(Collections.singleton(packageName));
    }

    private boolean hasRealVerifier() {
        return !(mProxy instanceof DomainVerificationProxyUnavailable);
    }

    /**
     * Validates parameters provided by an external caller. Checks that an ID is still live and that
     * any provided domains are valid. Should be called at the beginning of each API that takes in a
     * {@link UUID} domain set ID.
     */
    @GuardedBy("mLock")
    private DomainVerificationPkgState getAndValidateAttachedLocked(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean forAutoVerify)
            throws InvalidDomainSetException, NameNotFoundException {
        if (domainSetId == null) {
            throw new InvalidDomainSetException(null, null,
                    InvalidDomainSetException.REASON_ID_NULL);
        }

        DomainVerificationPkgState pkgState = mAttachedPkgStates.get(domainSetId);
        if (pkgState == null) {
            throw new InvalidDomainSetException(domainSetId, null,
                    InvalidDomainSetException.REASON_ID_INVALID);
        }

        String pkgName = pkgState.getPackageName();
        PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(pkgName);
        if (pkgSetting == null || pkgSetting.getPkg() == null) {
            throw DomainVerificationUtils.throwPackageUnavailable(pkgName);
        }

        if (CollectionUtils.isEmpty(domains)) {
            throw new InvalidDomainSetException(domainSetId, pkgState.getPackageName(),
                    InvalidDomainSetException.REASON_SET_NULL_OR_EMPTY);
        }
        AndroidPackage pkg = pkgSetting.getPkg();
        ArraySet<String> declaredDomains = forAutoVerify
                ? mCollector.collectAutoVerifyDomains(pkg)
                : mCollector.collectAllWebDomains(pkg);

        if (domains.retainAll(declaredDomains)) {
            throw new InvalidDomainSetException(domainSetId, pkgState.getPackageName(),
                    InvalidDomainSetException.REASON_UNKNOWN_DOMAIN);
        }

        return pkgState;
    }

    @Override
    public void verifyPackages(@Nullable List<String> packageNames, boolean reVerify) {
        mEnforcer.assertInternal(mConnection.get().getCallingUid());
        Set<String> packagesToBroadcast = new ArraySet<>();

        if (packageNames == null) {
            synchronized (mLock) {
                int pkgStatesSize = mAttachedPkgStates.size();
                for (int pkgStateIndex = 0; pkgStateIndex < pkgStatesSize; pkgStateIndex++) {
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(pkgStateIndex);
                    addIfShouldBroadcastLocked(packagesToBroadcast, pkgState, reVerify);
                }
            }
        } else {
            synchronized (mLock) {
                int size = packageNames.size();
                for (int index = 0; index < size; index++) {
                    String packageName = packageNames.get(index);
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.get(packageName);
                    if (pkgState != null) {
                        addIfShouldBroadcastLocked(packagesToBroadcast, pkgState, reVerify);
                    }
                }
            }
        }

        if (!packagesToBroadcast.isEmpty()) {
            mProxy.sendBroadcastForPackages(packagesToBroadcast);
        }
    }

    @GuardedBy("mLock")
    private void addIfShouldBroadcastLocked(@NonNull Collection<String> packageNames,
            @NonNull DomainVerificationPkgState pkgState, boolean reVerify) {
        if ((reVerify && pkgState.isHasAutoVerifyDomains()) || shouldReBroadcastPackage(pkgState)) {
            packageNames.add(pkgState.getPackageName());
        }
    }

    /**
     * Determine whether or not a broadcast should be sent at boot for the given {@param pkgState}.
     * Sends only if the only states recorded are default as decided by {@link
     * DomainVerificationManager#isStateDefault(int)}.
     *
     * If any other state is set, it's assumed that the domain verification agent is aware of the
     * package and has already scheduled future verification requests.
     */
    private boolean shouldReBroadcastPackage(DomainVerificationPkgState pkgState) {
        if (!pkgState.isHasAutoVerifyDomains()) {
            return false;
        }

        ArrayMap<String, Integer> stateMap = pkgState.getStateMap();
        int statesSize = stateMap.size();
        for (int stateIndex = 0; stateIndex < statesSize; stateIndex++) {
            Integer state = stateMap.valueAt(stateIndex);
            if (!DomainVerificationManager.isStateDefault(state)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void clearDomainVerificationState(@Nullable List<String> packageNames) {
        mEnforcer.assertInternal(mConnection.get().getCallingUid());
        synchronized (mLock) {
            if (packageNames == null) {
                int size = mAttachedPkgStates.size();
                for (int index = 0; index < size; index++) {
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(index);
                    String pkgName = pkgState.getPackageName();
                    PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(pkgName);
                    if (pkgSetting == null || pkgSetting.getPkg() == null) {
                        continue;
                    }
                    resetDomainState(pkgState, pkgSetting.getPkg());
                }
            } else {
                int size = packageNames.size();
                for (int index = 0; index < size; index++) {
                    String pkgName = packageNames.get(index);
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.get(pkgName);
                    PackageSetting pkgSetting = mConnection.get().getPackageSettingLocked(pkgName);
                    if (pkgSetting == null || pkgSetting.getPkg() == null) {
                        continue;
                    }
                    resetDomainState(pkgState, pkgSetting.getPkg());
                }
            }
        }
    }

    /**
     * Reset states that are mutable by the domain verification agent.
     */
    private void resetDomainState(@NonNull DomainVerificationPkgState pkgState,
            @NonNull AndroidPackage pkg) {
        ArrayMap<String, Integer> stateMap = pkgState.getStateMap();
        int size = stateMap.size();
        for (int index = size - 1; index >= 0; index--) {
            Integer state = stateMap.valueAt(index);
            boolean reset;
            switch (state) {
                case DomainVerificationState.STATE_SUCCESS:
                case DomainVerificationState.STATE_RESTORED:
                    reset = true;
                    break;
                default:
                    reset = state >= DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED;
                    break;
            }

            if (reset) {
                stateMap.removeAt(index);
            }
        }

        applyImmutableState(pkgState, mCollector.collectAutoVerifyDomains(pkg));
    }

    @Override
    public void clearUserSelections(@Nullable List<String> packageNames, @UserIdInt int userId) {
        mEnforcer.assertInternal(mConnection.get().getCallingUid());
        synchronized (mLock) {
            if (packageNames == null) {
                int size = mAttachedPkgStates.size();
                for (int index = 0; index < size; index++) {
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.valueAt(index);
                    if (userId == UserHandle.USER_ALL) {
                        pkgState.removeAllUsers();
                    } else {
                        pkgState.removeUser(userId);
                    }
                }
            } else {
                int size = packageNames.size();
                for (int index = 0; index < size; index++) {
                    String pkgName = packageNames.get(index);
                    DomainVerificationPkgState pkgState = mAttachedPkgStates.get(pkgName);
                    if (userId == UserHandle.USER_ALL) {
                        pkgState.removeAllUsers();
                    } else {
                        pkgState.removeUser(userId);
                    }
                }
            }
        }
    }

    public interface Connection {

        /**
         * Notify that a settings change has been made and that eventually
         * {@link #writeSettings(TypedXmlSerializer)} should be invoked by the parent.
         */
        void scheduleWriteSettings();

        /**
         * Delegate to {@link Binder#getCallingUid()} to allow mocking in tests.
         */
        int getCallingUid();

        /**
         * Delegate to {@link UserHandle#getCallingUserId()} to allow mocking in tests.
         */
        @UserIdInt
        int getCallingUserId();

        /**
         * @see DomainVerificationProxy.Connection#schedule(int, java.lang.Object)
         */
        void schedule(int code, @Nullable Object object);

        boolean isCallerPackage(int callingUid, @NonNull String packageName);

        /**
         * This can only be called when the internal {@link #mLock} is held. Otherwise it's possible
         * to deadlock with {@link PackageManagerService}.
         */
        @GuardedBy("mLock")
        @Nullable
        PackageSetting getPackageSettingLocked(@NonNull String pkgName);

        /**
         * This can only be called when the internal {@link #mLock} is held. Otherwise it's possible
         * to deadlock with {@link PackageManagerService}.
         */
        @GuardedBy("mLock")
        @Nullable
        AndroidPackage getPackageLocked(@NonNull String pkgName);
    }
}
