/*
 *  Copyright (c) 2026 Pavel Mironchik
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.dailyprogress

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForcedCommandSshHostKeyParsingTest {
    @Test
    fun `base64-only host key defaults to ssh-ed25519 and configured host`() {
        val knownHostsEntry = "AAAAC3NzaC1lZDI1NTE5AAAAIFakeBase64HostKey".toKnownHostsEntry(host = "51.210.241.242", port = 22)

        assertEquals(
            "51.210.241.242 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeBase64HostKey",
            knownHostsEntry,
        )
    }

    @Test
    fun `algorithm plus base64 is synthesized for non-default port`() {
        val knownHostsEntry =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeBase64HostKey comment".toKnownHostsEntry(
                host = "example.org",
                port = 2222,
            )

        assertEquals(
            "[example.org]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeBase64HostKey",
            knownHostsEntry,
        )
    }

    @Test
    fun `legacy known_hosts line stays usable`() {
        val knownHostsEntry =
            " [example.org]:2222   ssh-ed25519   AAAAC3NzaC1lZDI1NTE5AAAAIFakeBase64HostKey   saved-comment  ".toKnownHostsEntry(
                host = "ignored.example",
                port = 22,
            )

        assertEquals(
            "[example.org]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeBase64HostKey saved-comment",
            knownHostsEntry,
        )
    }

    @Test
    fun `algorithm without base64 is rejected early`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                "ssh-ed25519".toKnownHostsEntry(host = "51.210.241.242", port = 22)
            }

        assertEquals("Forced-command SSH host key is missing the base64 key body", exception.message)
    }
}
