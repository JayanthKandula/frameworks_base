/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm.permission;

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PermissionInfo;
import android.permission.PermissionManagerInternal;

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Internal interfaces services.
 *
 * TODO: Should be merged into PermissionManagerInternal, but currently uses internal classes.
 */
public abstract class PermissionManagerServiceInternal extends PermissionManagerInternal
        implements LegacyPermissionDataProvider {
    /**
     * Provider for package names.
     */
    public interface PackagesProvider {

        /**
         * Gets the packages for a given user.
         * @param userId The user id.
         * @return The package names.
         */
        String[] getPackages(int userId);
    }

    /**
     * Provider for package names.
     */
    public interface SyncAdapterPackagesProvider {

        /**
         * Gets the sync adapter packages for given authority and user.
         * @param authority The authority.
         * @param userId The user id.
         * @return The package names.
         */
        String[] getPackages(String authority, int userId);
    }

    /**
     * Provider for default browser
     */
    public interface DefaultBrowserProvider {

        /**
         * Get the package name of the default browser.
         *
         * @param userId the user id
         *
         * @return the package name of the default browser, or {@code null} if none
         */
        @Nullable
        String getDefaultBrowser(@UserIdInt int userId);

        /**
         * Set the package name of the default browser.
         *
         * @param packageName package name of the default browser, or {@code null} to remove
         * @param userId the user id
         *
         * @return whether the default browser was successfully set.
         */
        boolean setDefaultBrowser(@Nullable String packageName, @UserIdInt int userId);

        /**
         * Set the package name of the default browser asynchronously.
         *
         * @param packageName package name of the default browser, or {@code null} to remove
         * @param userId the user id
         */
        void setDefaultBrowserAsync(@Nullable String packageName, @UserIdInt int userId);
    }

    /**
     * Provider for default dialer
     */
    public interface DefaultDialerProvider {

        /**
         * Get the package name of the default dialer.
         *
         * @param userId the user id
         *
         * @return the package name of the default dialer, or {@code null} if none
         */
        @Nullable
        String getDefaultDialer(@UserIdInt int userId);
    }

    /**
     * Provider for default home
     */
    public interface DefaultHomeProvider {

        /**
         * Get the package name of the default home.
         *
         * @param userId the user id
         *
         * @return the package name of the default home, or {@code null} if none
         */
        @Nullable
        String getDefaultHome(@UserIdInt int userId);

        /**
         * Set the package name of the default home.
         *
         * @param packageName package name of the default home, or {@code null} to remove
         * @param userId the user id
         * @param callback the callback made after the default home as been updated
         */
        void setDefaultHomeAsync(@Nullable String packageName, @UserIdInt int userId,
                @NonNull Consumer<Boolean> callback);
    }

    /**
     * Callbacks invoked when interesting actions have been taken on a permission.
     * <p>
     * NOTE: The current arguments are merely to support the existing use cases. This
     * needs to be properly thought out with appropriate arguments for each of the
     * callback methods.
     */
    public static class PermissionCallback {
        public void onGidsChanged(@AppIdInt int appId, @UserIdInt int userId) {
        }
        public void onPermissionChanged() {
        }
        public void onPermissionGranted(int uid, @UserIdInt int userId) {
        }
        public void onInstallPermissionGranted() {
        }
        public void onPermissionRevoked(int uid, @UserIdInt int userId, String reason) {
        }
        public void onInstallPermissionRevoked() {
        }
        public void onPermissionUpdated(@UserIdInt int[] updatedUserIds, boolean sync) {
        }
        public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds, boolean sync,
                int uid) {
            onPermissionUpdated(updatedUserIds, sync);
        }
        public void onPermissionRemoved() {
        }
        public void onInstallPermissionUpdated() {
        }
        public void onInstallPermissionUpdatedNotifyListener(int uid) {
            onInstallPermissionUpdated();
        }
    }

    public abstract void systemReady();

    /**
     * Get whether permission review is required for a package.
     *
     * @param packageName the name of the package
     * @param userId the user ID
     * @return whether permission review is required
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract boolean isPermissionsReviewRequired(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Update permissions when a package changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param packageName The package that is updated
     * @param pkg The package that is updated, or {@code null} if package is deleted
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    public abstract void updatePermissions(@NonNull String packageName,
            @Nullable AndroidPackage pkg);

    /**
     * Update all permissions for all apps.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param volumeUuid The volume of the packages to be updated, {@code null} for all volumes
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    public abstract void updateAllPermissions(@Nullable String volumeUuid, boolean sdkUpdate);

    /**
     * Reset the runtime permission state changes for a package.
     *
     * TODO(zhanghai): Turn this into package change callback?
     *
     * @param pkg the package
     * @param userId the user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract void resetRuntimePermissions(@NonNull AndroidPackage pkg,
            @UserIdInt int userId);

    /**
     * Reset the runtime permission state changes for all packages.
     *
     * @param userId the user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract void resetAllRuntimePermissions(@UserIdInt int userId);

    /**
     * Read legacy permission state from package settings.
     *
     * TODO(zhanghai): This is a temporary method because we should not expose
     * {@code PackageSetting} which is a implementation detail that permission should not know.
     * Instead, it should retrieve the legacy state via a defined API.
     */
    public abstract void readLegacyPermissionStateTEMP();

    /**
     * Write legacy permission state to package settings.
     *
     * TODO(zhanghai): This is a temporary method and should be removed once we migrated persistence
     * for permission.
     */
    public abstract void writeLegacyPermissionStateTEMP();

    /**
     * Notify that a user has been removed and its permission state should be removed as well.
     */
    public abstract void onUserRemoved(@UserIdInt int userId);

    /**
     * Get all the permissions granted to a package.
     *
     * @param packageName the name of the package
     * @param userId the user ID
     * @return the names of the granted permissions
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    public abstract Set<String> getGrantedPermissions(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Get the GIDs of a permission.
     *
     * @param permissionName the name of the permission
     * @param userId the user ID
     * @return the GIDs of the permission
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    public abstract int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId);

    /**
     * Get the packages that have requested an app op permission.
     *
     * @param permissionName the name of the app op permission
     * @return the names of the packages that have requested the app op permission
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    public abstract String[] getAppOpPermissionPackages(@NonNull String permissionName);

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    @Nullable
    public abstract Permission getPermissionTEMP(@NonNull String permName);

    /** Get all permissions that have a certain protection */
    public abstract @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtection(
            @PermissionInfo.Protection int protection);

    /** Get all permissions that have certain protection flags */
    public abstract @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtectionFlags(
            @PermissionInfo.ProtectionFlags int protectionFlags);

    /**
     * Returns the delegate used to influence permission checking.
     *
     * @return The delegate instance.
     */
    public abstract @Nullable CheckPermissionDelegate getCheckPermissionDelegate();

    /**
     * Sets the delegate used to influence permission checking.
     *
     * @param delegate A delegate instance or {@code null} to clear.
     */
    public abstract void setCheckPermissionDelegate(@Nullable CheckPermissionDelegate delegate);

    /**
     * Sets the dialer application packages provider.
     * @param provider The provider.
     */
    public abstract void setDialerAppPackagesProvider(PackagesProvider provider);

    /**
     * Set the location extra packages provider.
     * @param provider The packages provider.
     */
    public abstract  void setLocationExtraPackagesProvider(PackagesProvider provider);

    /**
     * Sets the location provider packages provider.
     * @param provider The packages provider.
     */
    public abstract void setLocationPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SIM call manager packages provider.
     * @param provider The provider.
     */
    public abstract void setSimCallManagerPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SMS application packages provider.
     * @param provider The provider.
     */
    public abstract void setSmsAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sync adapter packages provider.
     * @param provider The provider.
     */
    public abstract void setSyncAdapterPackagesProvider(SyncAdapterPackagesProvider provider);

    /**
     * Sets the Use Open Wifi packages provider.
     * @param provider The packages provider.
     */
    public abstract void setUseOpenWifiAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the voice interaction packages provider.
     * @param provider The packages provider.
     */
    public abstract void setVoiceInteractionPackagesProvider(PackagesProvider provider);

    /**
     * Sets the default browser provider.
     *
     * @param provider the provider
     */
    public abstract void setDefaultBrowserProvider(@NonNull DefaultBrowserProvider provider);

    /**
     * Sets the package name of the default browser provider for the given user.
     *
     * @param packageName The package name of the default browser or {@code null}
     *          to clear the default browser
     * @param async If {@code true}, set the default browser asynchronously,
     *          otherwise set it synchronously
     * @param doGrant If {@code true} and if {@code packageName} is not {@code null},
     *          perform default permission grants on the browser, otherwise skip the
     *          default permission grants.
     * @param userId The user to set the default browser for.
     */
    public abstract void setDefaultBrowser(@Nullable String packageName, boolean async,
            boolean doGrant, @UserIdInt int userId);

    /**
     * Sets the default dialer provider.
     *
     * @param provider the provider
     */
    public abstract void setDefaultDialerProvider(@NonNull DefaultDialerProvider provider);

    /**
     * Sets the default home provider.
     *
     * @param provider the provider
     */
    public abstract void setDefaultHomeProvider(@NonNull DefaultHomeProvider provider);

    /**
     * Asynchronously sets the package name of the default home provider for the given user.
     *
     * @param packageName The package name of the default home or {@code null}
     *          to clear the default browser
     * @param userId The user to set the default browser for
     * @param callback Invoked after the default home has been set
     */
    public abstract void setDefaultHome(@Nullable String packageName, @UserIdInt int userId,
            @NonNull Consumer<Boolean> callback);

    /**
     * Returns the default browser package name for the given user.
     */
    @Nullable
    public abstract String getDefaultBrowser(@UserIdInt int userId);

    /**
     * Returns the default dialer package name for the given user.
     */
    @Nullable
    public abstract String getDefaultDialer(@UserIdInt int userId);

    /**
     * Returns the default home package name for the given user.
     */
    @Nullable
    public abstract String getDefaultHome(@UserIdInt int userId);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultSimCallManager(
            @NonNull String packageName, @UserIdInt int userId);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultUseOpenWifiApp(
            @NonNull String packageName, @UserIdInt int userId);

    /** Called when a new user has been created. */
    public abstract void onNewUserCreated(@UserIdInt int userId);

    /**
     * Removes invalid permissions which are not {@link PermissionInfo#FLAG_HARD_RESTRICTED} or
     * {@link PermissionInfo#FLAG_SOFT_RESTRICTED} from the input.
     */
    public abstract void retainHardAndSoftRestrictedPermissions(
            @NonNull List<String> permissionNames);

    /**
     * Read legacy permissions from legacy permission settings.
     *
     * TODO(zhanghai): This is a temporary method because we should not expose
     * {@code LegacyPermissionSettings} which is a implementation detail that permission should not
     * know. Instead, it should retrieve the legacy permissions via a defined API.
     */
    public abstract void readLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings);

    /**
     * Write legacy permissions to legacy permission settings.
     *
     * TODO(zhanghai): This is a temporary method and should be removed once we migrated persistence
     * for permission.
     */
    public abstract void writeLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings);

    /**
     * Callback when a package has been added.
     *
     * @param pkg the added package
     * @param isInstantApp whether the added package is an instant app
     * @param oldPkg the old package, or {@code null} if none
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract void onPackageAdded(@NonNull AndroidPackage pkg, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg);

    /**
     * Callback when a package has been installed for certain users.
     *
     * @param pkg the installed package
     * @param grantedPermissions the permissions to be granted
     * @param allowlistedRestrictedPermissions the restricted permissions to be allowlisted
     * @param autoRevokePermissionsMode the auto revoke permissions mode for this package
     * @param userIds the user IDs this package is installed for
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract void onPackageInstalled(@NonNull AndroidPackage pkg,
            @NonNull List<String> grantedPermissions,
            @NonNull List<String> allowlistedRestrictedPermissions,
            int autoRevokePermissionsMode, @NonNull int[] userIds);

    /**
     * Callback when a package has been removed.
     *
     * @param pkg the removed package
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract void onPackageRemoved(@NonNull AndroidPackage pkg);

    /**
     * Callback when the state for a package has been removed.
     *
     * @param packageName the name of the removed package
     * @param appId the app ID of the removed package
     * @param pkg the removed package, or {@code null} if unavailable
     * @param sharedUserPkgs the packages that are in the same shared user
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public abstract void onPackageStateRemoved(@NonNull String packageName, int appId,
            @Nullable AndroidPackage pkg, @NonNull List<AndroidPackage> sharedUserPkgs);

    /**
     * Check whether a permission can be propagated to instant app.
     *
     * @param permissionName the name of the permission
     * @return whether the permission can be propagated
     */
    public abstract boolean canPropagatePermissionToInstantApp(@NonNull String permissionName);
}
