package com.goplan.app.data.sync

import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveAuthManagerTest {

    @Test
    fun testInitialState_whenNoAccount_signedInAccountIsNull() {
        val authManager = GoogleDriveAuthManager(context = null)
        assertNull(authManager.signedInAccount.value)
    }

    @Test
    fun testInitialState_whenExistingAccount_signedInAccountIsRestored() {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        assertEquals(account, authManager.signedInAccount.value)
    }

    @Test
    fun testHandleSignInResult_nullIntent_returnsFailure() {
        val authManager = GoogleDriveAuthManager(context = null)
        val result = authManager.handleSignInResult(null)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
        assertEquals("Intent data is null", exception?.message)
    }

    @Test
    fun testHandleSignInResult_emptyOrCancelledIntent_returnsFailure() {
        val authManager = GoogleDriveAuthManager(
            context = null,
            signInResultExtractor = {
                throw Exception("Sign in cancelled or failed")
            }
        )
        val dummyIntent = Intent()
        val result = authManager.handleSignInResult(dummyIntent)

        assertTrue(result.isFailure)
        assertNull(authManager.signedInAccount.value)
    }

    @Test
    fun testHandleSignInResult_taskWithException_returnsFailure() {
        val apiException = ApiException(Status(CommonStatusCodes.SIGN_IN_REQUIRED, "Sign in required"))
        val authManager = GoogleDriveAuthManager(
            context = null,
            signInResultExtractor = {
                Tasks.forException(apiException)
            }
        )
        val dummyIntent = Intent()
        val result = authManager.handleSignInResult(dummyIntent)

        assertTrue(result.isFailure)
        assertEquals(apiException, result.exceptionOrNull())
        assertNull(authManager.signedInAccount.value)
    }

    @Test
    fun testHandleSignInResult_successfulTask_updatesStateAndReturnsSuccess() {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            signInResultExtractor = {
                Tasks.forResult(account)
            }
        )
        val dummyIntent = Intent()
        val result = authManager.handleSignInResult(dummyIntent)

        assertTrue(result.isSuccess)
        assertEquals(account, result.getOrNull())
        assertEquals(account, authManager.signedInAccount.value)
    }

    @Test
    fun testSignOut_clearsSignedInAccountAndCallsCallback() {
        val account = GoogleSignInAccount.createDefault()
        val authManager = GoogleDriveAuthManager(
            context = null,
            existingAccountProvider = { account }
        )
        assertEquals(account, authManager.signedInAccount.value)

        var onCompleteCalled = false
        authManager.signOut {
            onCompleteCalled = true
        }

        assertNull(authManager.signedInAccount.value)
        assertTrue(onCompleteCalled)
    }

    @Test
    fun testIsConfigured_whenContextIsNull_returnsFalse() {
        val authManager = GoogleDriveAuthManager(context = null)
        assertFalse(authManager.isConfigured())
    }
}
