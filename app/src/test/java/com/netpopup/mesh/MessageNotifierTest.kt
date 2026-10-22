package com.netpopup.mesh

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MessageNotifierTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun notifier() = MessageNotifier(context).also { it.createChannel() }

    @Test fun notificationRevealsNothingAndIsHiddenOnTheLockScreen() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier().show()

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("texte fixe", "New message", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull("pas de corps de texte", posted.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull("pas de sous-texte", posted.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
    }

    @Test fun burstsAreGroupedIntoASingleNotification() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = notifier()
        repeat(5) { n.show() }
        assertEquals("une seule notification, remplacée à chaque message", 1, shadowOf(manager).allNotifications.size)
    }

    @Test fun clearRemovesIt() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = notifier()
        n.show()
        n.clear()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun nothingIsPostedWithoutThePermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier().show()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun channelIsPrivateByDefault() {
        notifier()
        val channel = manager.getNotificationChannel("netpopup_messages")
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
    }
}
