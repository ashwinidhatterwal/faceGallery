package com.mosaic.gallery

import org.junit.Test
import org.junit.Assert.*

class RefinementSweepTest {
    @Test fun thousandsOfPhotosRegroupOnceAfterAllRefinements(){
        var refined=0;var grouped=0
        RefinementSweep.run((1..1200).toList(),{true},{refined++;1},{assertEquals(1200,refined);grouped++})
        assertEquals(1200,refined);assertEquals(1,grouped)
    }
    @Test fun noProgressDoesNotRepeatOrRegroup(){
        var refined=0;var grouped=0
        RefinementSweep.run((1..100).toList(),{true},{refined++;0},{grouped++})
        assertEquals(100,refined);assertEquals(0,grouped)
    }
    @Test fun cancellationKeepsRemainingItemsForLaterAndDoesNotMarkGrouped(){
        var running=true;var refined=0;var grouped=0
        RefinementSweep.run((1..100).toList(),{running},{refined++;running=false;1},{grouped++})
        assertEquals(1,refined);assertEquals(0,grouped)
    }
    @Test fun cancellationAfterLastCommitDoesNotRegroup(){
        var running=true;var grouped=0
        RefinementSweep.run(listOf(1),{running},{running=false;1},{grouped++})
        assertEquals(0,grouped)
    }
    @Test fun emptyWorkDoesNotRegroup(){
        RefinementSweep.run(emptyList<Int>(),{true},{fail("No item expected");1},{fail("No changes expected")})
    }
}
