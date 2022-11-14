/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.android.systemui.SystemUIAppComponentFactoryBase
import com.android.systemui.SystemUIAppComponentFactoryBase.ContextAvailableCallback
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.shared.keyguard.data.content.KeyguardQuickAffordanceProviderContract as Contract
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

class KeyguardQuickAffordanceProvider :
    ContentProvider(), SystemUIAppComponentFactoryBase.ContextInitializer {

    @Inject lateinit var interactor: KeyguardQuickAffordanceInteractor

    private lateinit var contextAvailableCallback: ContextAvailableCallback

    private val uriMatcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(
                Contract.AUTHORITY,
                Contract.SlotTable.TABLE_NAME,
                MATCH_CODE_ALL_SLOTS,
            )
            addURI(
                Contract.AUTHORITY,
                Contract.AffordanceTable.TABLE_NAME,
                MATCH_CODE_ALL_AFFORDANCES,
            )
            addURI(
                Contract.AUTHORITY,
                Contract.SelectionTable.TABLE_NAME,
                MATCH_CODE_ALL_SELECTIONS,
            )
        }

    override fun onCreate(): Boolean {
        return true
    }

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        contextAvailableCallback.onContextAvailable(checkNotNull(context))
        super.attachInfo(context, info)
    }

    override fun setContextAvailableCallback(callback: ContextAvailableCallback) {
        contextAvailableCallback = callback
    }

    override fun getType(uri: Uri): String? {
        val prefix =
            when (uriMatcher.match(uri)) {
                MATCH_CODE_ALL_SLOTS,
                MATCH_CODE_ALL_AFFORDANCES,
                MATCH_CODE_ALL_SELECTIONS -> "vnd.android.cursor.dir/vnd."
                else -> null
            }

        val tableName =
            when (uriMatcher.match(uri)) {
                MATCH_CODE_ALL_SLOTS -> Contract.SlotTable.TABLE_NAME
                MATCH_CODE_ALL_AFFORDANCES -> Contract.AffordanceTable.TABLE_NAME
                MATCH_CODE_ALL_SELECTIONS -> Contract.SelectionTable.TABLE_NAME
                else -> null
            }

        if (prefix == null || tableName == null) {
            return null
        }

        return "$prefix${Contract.AUTHORITY}.$tableName"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != MATCH_CODE_ALL_SELECTIONS) {
            throw UnsupportedOperationException()
        }

        return insertSelection(values)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            MATCH_CODE_ALL_AFFORDANCES -> queryAffordances()
            MATCH_CODE_ALL_SLOTS -> querySlots()
            MATCH_CODE_ALL_SELECTIONS -> querySelections()
            else -> null
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        Log.e(TAG, "Update is not supported!")
        return 0
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (uriMatcher.match(uri) != MATCH_CODE_ALL_SELECTIONS) {
            throw UnsupportedOperationException()
        }

        return deleteSelection(uri, selectionArgs)
    }

    private fun insertSelection(values: ContentValues?): Uri? {
        if (values == null) {
            throw IllegalArgumentException("Cannot insert selection, no values passed in!")
        }

        if (!values.containsKey(Contract.SelectionTable.Columns.SLOT_ID)) {
            throw IllegalArgumentException(
                "Cannot insert selection, " +
                    "\"${Contract.SelectionTable.Columns.SLOT_ID}\" not specified!"
            )
        }

        if (!values.containsKey(Contract.SelectionTable.Columns.AFFORDANCE_ID)) {
            throw IllegalArgumentException(
                "Cannot insert selection, " +
                    "\"${Contract.SelectionTable.Columns.AFFORDANCE_ID}\" not specified!"
            )
        }

        val slotId = values.getAsString(Contract.SelectionTable.Columns.SLOT_ID)
        val affordanceId = values.getAsString(Contract.SelectionTable.Columns.AFFORDANCE_ID)

        if (slotId.isNullOrEmpty()) {
            throw IllegalArgumentException("Cannot insert selection, slot ID was empty!")
        }

        if (affordanceId.isNullOrEmpty()) {
            throw IllegalArgumentException("Cannot insert selection, affordance ID was empty!")
        }

        val success = runBlocking {
            interactor.select(
                slotId = slotId,
                affordanceId = affordanceId,
            )
        }

        return if (success) {
            Log.d(TAG, "Successfully selected $affordanceId for slot $slotId")
            context?.contentResolver?.notifyChange(Contract.SelectionTable.URI, null)
            Contract.SelectionTable.URI
        } else {
            Log.d(TAG, "Failed to select $affordanceId for slot $slotId")
            null
        }
    }

    private fun querySelections(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.SelectionTable.Columns.SLOT_ID,
                    Contract.SelectionTable.Columns.AFFORDANCE_ID,
                )
            )
            .apply {
                val affordanceIdsBySlotId = runBlocking { interactor.getSelections() }
                affordanceIdsBySlotId.entries.forEach { (slotId, affordanceIds) ->
                    affordanceIds.forEach { affordanceId ->
                        addRow(
                            arrayOf(
                                slotId,
                                affordanceId,
                            )
                        )
                    }
                }
            }
    }

    private fun queryAffordances(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.AffordanceTable.Columns.ID,
                    Contract.AffordanceTable.Columns.NAME,
                    Contract.AffordanceTable.Columns.ICON,
                )
            )
            .apply {
                interactor.getAffordancePickerRepresentations().forEach { representation ->
                    addRow(
                        arrayOf(
                            representation.id,
                            representation.name,
                            representation.iconResourceId,
                        )
                    )
                }
            }
    }

    private fun querySlots(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.SlotTable.Columns.ID,
                    Contract.SlotTable.Columns.CAPACITY,
                )
            )
            .apply {
                interactor.getSlotPickerRepresentations().forEach { representation ->
                    addRow(
                        arrayOf(
                            representation.id,
                            representation.maxSelectedAffordances,
                        )
                    )
                }
            }
    }

    private fun deleteSelection(
        uri: Uri,
        selectionArgs: Array<out String>?,
    ): Int {
        if (selectionArgs == null) {
            throw IllegalArgumentException(
                "Cannot delete selection, selection arguments not included!"
            )
        }

        val (slotId, affordanceId) =
            when (selectionArgs.size) {
                1 -> Pair(selectionArgs[0], null)
                2 -> Pair(selectionArgs[0], selectionArgs[1])
                else ->
                    throw IllegalArgumentException(
                        "Cannot delete selection, selection arguments has wrong size, expected to" +
                            " have 1 or 2 arguments, had ${selectionArgs.size} instead!"
                    )
            }

        val deleted = runBlocking {
            interactor.unselect(
                slotId = slotId,
                affordanceId = affordanceId,
            )
        }

        return if (deleted) {
            Log.d(TAG, "Successfully unselected $affordanceId for slot $slotId")
            context?.contentResolver?.notifyChange(uri, null)
            1
        } else {
            Log.d(TAG, "Failed to unselect $affordanceId for slot $slotId")
            0
        }
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceProvider"
        private const val MATCH_CODE_ALL_SLOTS = 1
        private const val MATCH_CODE_ALL_AFFORDANCES = 2
        private const val MATCH_CODE_ALL_SELECTIONS = 3
    }
}
