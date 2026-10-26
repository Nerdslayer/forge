package forge.game.phase;

import org.testng.Assert;
import org.testng.annotations.Test;

public class StackResolutionTrackerTest {
    @Test
    public void largeFiniteStackKeepsMakingProgress() {
        StackResolutionTracker tracker = new StackResolutionTracker();
        for (int size = 1500; size > 0; size--) {
            tracker.beforeResolution(size);
            tracker.afterResolution(size - 1, "same ability");
            Assert.assertFalse(tracker.warningDue());
            Assert.assertFalse(tracker.isRepeating("same ability"));
        }
    }

    @Test
    public void oscillatingStackCannotResetCounterOnEveryDecrease() {
        StackResolutionTracker tracker = new StackResolutionTracker();
        tracker.beforeResolution(2);
        for (int i = 0; i < 220; i++) {
            tracker.afterResolution(i % 2 == 0 ? 3 : 2, "repeated");
        }
        Assert.assertTrue(tracker.warningDue());
        Assert.assertTrue(tracker.isRepeating("repeated"));
        Assert.assertFalse(tracker.isRepeating("unrelated optional trigger"));
    }

    @Test
    public void growingStackWarnsWithoutRequiringAnExactRepeat() {
        StackResolutionTracker tracker = new StackResolutionTracker();
        tracker.beforeResolution(1);
        for (int i = 1; i <= 200; i++) {
            tracker.afterResolution(i + 1, "unique " + i);
        }
        Assert.assertTrue(tracker.warningDue());
        Assert.assertFalse(tracker.isRepeating("unique 200"));
    }

    @Test
    public void continuingGrantsAnotherWindowWithoutForgettingRepetition() {
        StackResolutionTracker tracker = stalledTracker();
        tracker.acknowledgeWarning();
        Assert.assertFalse(tracker.warningDue());
        Assert.assertTrue(tracker.isRepeating("repeated"));
        for (int i = 0; i < 199; i++) {
            tracker.afterResolution(2, "repeated");
        }
        Assert.assertFalse(tracker.warningDue());
        tracker.afterResolution(2, "repeated");
        Assert.assertTrue(tracker.warningDue());
    }

    @Test
    public void newMinimumStartsAFreshWindow() {
        StackResolutionTracker tracker = stalledTracker();
        tracker.afterResolution(1, "repeated");
        Assert.assertEquals(tracker.getResolutionsWithoutProgress(), 0);
        Assert.assertFalse(tracker.warningDue());
        Assert.assertFalse(tracker.isRepeating("repeated"));
    }

    @Test
    public void historyIsBoundedAndForgetsOldAbilities() {
        StackResolutionTracker tracker = stalledTracker();
        for (int i = 0; i < 64; i++) {
            tracker.afterResolution(2, "new ability");
        }
        Assert.assertFalse(tracker.isRepeating("repeated"));
        Assert.assertTrue(tracker.isRepeating("new ability"));
    }

    @Test
    public void emptyStackAndExplicitResetClearOldEpisodes() {
        StackResolutionTracker tracker = stalledTracker();
        tracker.afterResolution(0, "repeated");
        Assert.assertFalse(tracker.warningDue());
        tracker = stalledTracker();
        tracker.reset();
        tracker.beforeResolution(300);
        tracker.afterResolution(299, "different");
        Assert.assertFalse(tracker.warningDue());
        Assert.assertEquals(tracker.getResolutionsWithoutProgress(), 0);
    }

    private StackResolutionTracker stalledTracker() {
        StackResolutionTracker tracker = new StackResolutionTracker();
        tracker.beforeResolution(2);
        for (int i = 0; i < 200; i++) {
            tracker.afterResolution(2, "repeated");
        }
        return tracker;
    }
}
