package it.allard.multistream.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun newerPatchIsAnUpdate() = assertTrue(isNewer("0.2.2", "0.2.1"))

    @Test fun newerMinorIsAnUpdate() = assertTrue(isNewer("0.3.0", "0.2.9"))

    @Test fun sameVersionIsNotAnUpdate() = assertFalse(isNewer("0.2.1", "0.2.1"))

    @Test fun olderVersionIsNotAnUpdate() = assertFalse(isNewer("0.2.0", "0.2.1"))

    @Test fun leadingVTagIsIgnored() = assertTrue(isNewer("v0.2.2", "0.2.1"))

    @Test fun numericSegmentsCompareAsNumbers() = assertTrue(isNewer("0.10.0", "0.9.0"))

    @Test fun unequalLengthShorterIsNotNewer() = assertFalse(isNewer("0.2", "0.2.1"))

    @Test fun unequalLengthLongerCanBeNewer() = assertTrue(isNewer("0.2.1", "0.2"))
}
