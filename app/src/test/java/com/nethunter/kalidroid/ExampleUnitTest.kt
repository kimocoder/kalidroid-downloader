package com.nethunter.kalidroid

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun isNewerCommit_comparesCommitShas() {
        assertTrue(
            MainActivity.isNewerCommit(
                "0028b2a4c4906f45c195d2b8016dcbda4e7b77eb",
                "6afe32812322b3e9d41f302fae508ce27e2cfed4"
            )
        )
        assertFalse(
            MainActivity.isNewerCommit(
                "0028b2a4c4906f45c195d2b8016dcbda4e7b77eb",
                "0028b2a4c4906f45c195d2b8016dcbda4e7b77eb"
            )
        )
        assertFalse(
            MainActivity.isNewerCommit(
                "0028b2a4c4906f45c195d2b8016dcbda4e7b77eb",
                "0028b2a"
            )
        )
        assertFalse(MainActivity.isNewerCommit("", "0028b2a"))
        assertFalse(MainActivity.isNewerCommit("0028b2a", ""))
    }
}