package forge.card.mana;

import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Class ParserCardnameTxtManaCost.
 */
public class ManaCostParser implements IParserManaCost {
    private static final Pattern BRACED_MANA_SYMBOL = Pattern.compile("\\{([^{}]+)\\}");
    private final String[] cost;
    private int nextToken;
    private int genericCost;

    /**
     * Parse the given cost and output formatted cost string
     * 
     * @param cost
     */
    public static String parse(final String cost) {
    	final ManaCostParser parser = new ManaCostParser(cost);
    	final ManaCost manaCost = new ManaCost(parser);
    	return manaCost.toString();
    }

    /**
     * Instantiates a new parser cardname txt mana cost.
     * 
     * @param cost
     *            the cost
     */
    public ManaCostParser(final String cost) {
        this.cost = tokenize(cost);
        this.nextToken = 0;
        this.genericCost = 0;
    }

    /** Accept both Forge's script form ("3 W") and its display form ("{3}{W}"). */
    private static String[] tokenize(final String rawCost) {
        if (rawCost == null) {
            return new String[] {""};
        }
        if (!rawCost.contains("{")) {
            return rawCost.split(" ");
        }

        final String trimmedCost = rawCost.trim();
        final Matcher matcher = BRACED_MANA_SYMBOL.matcher(trimmedCost);
        final List<String> tokens = new ArrayList<>();
        int end = 0;
        while (matcher.find()) {
            if (!trimmedCost.substring(end, matcher.start()).isBlank()) {
                return rawCost.split(" ");
            }
            tokens.add(matcher.group(1).trim());
            end = matcher.end();
        }
        if (tokens.isEmpty() || !trimmedCost.substring(end).isBlank()) {
            return rawCost.split(" ");
        }
        return tokens.toArray(String[]::new);
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.card.CardManaCost.ManaParser#getTotalGenericCost()
     */
    @Override
    public final int getTotalGenericCost() {
        if (this.hasNext()) {
            throw new RuntimeException("Generic cost should be obtained after iteration is complete");
        }
        return this.genericCost;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#hasNext()
     */
    @Override
    public final boolean hasNext() {
        return this.nextToken < this.cost.length;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#next()
     */
    @Override
    public final ManaCostShard next() {
        final String unparsed = this.cost[this.nextToken++];
        // consider negation sign
        Integer i = Ints.tryParse(unparsed);
        if (i != null) {
            this.genericCost += i;
            return null;
        }

        return ManaCostShard.parseNonGeneric(unparsed);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#remove()
     */
    @Override
    public void remove() {
    } // unsupported
}
