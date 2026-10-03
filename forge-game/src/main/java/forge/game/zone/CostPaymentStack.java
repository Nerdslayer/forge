package forge.game.zone;

import java.util.Iterator;
import java.util.Stack;

import forge.game.cost.CostPart;
import forge.game.cost.CostPayment;
import forge.game.Game;

/*
 * simple stack wrapper class for tracking cost payments (mainly for triggers to use)
 */
public class CostPaymentStack implements Iterable<CostPaymentStack.Entry> {

    private Stack<Entry> stack;
    private final Game game;

    public CostPaymentStack() {
        this(null);
    }

    public CostPaymentStack(final Game game) {
        this.game = game;
        stack = new Stack<>();
    }

    public Entry push(final CostPart cost, final CostPayment payment) {
        invalidateAnalysisState();
        return stack.push(new Entry(cost, payment));
    }

    public Entry pop() {
        final Entry entry = stack.pop();
        invalidateAnalysisState();
        return entry;
    }

    public Entry peek() {
        if (stack.empty()) {
            return null;
        }

        return stack.peek();
    }

    public void clear() {
        if (!stack.isEmpty()) { invalidateAnalysisState(); }
        stack.clear();
    }

    private void invalidateAnalysisState() {
        if (game != null) { game.invalidateAnalysisState(); }
    }

    @Override
    public Iterator<Entry> iterator() {
        return stack.iterator();
    }

    @Override
    public String toString() {
        return stack.toString();
    }

    public record Entry(CostPart cost, CostPayment payment) {

    }
}
