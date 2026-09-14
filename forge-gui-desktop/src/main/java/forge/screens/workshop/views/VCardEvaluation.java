package forge.screens.workshop.views;

import forge.ai.CardDefinitionValueEvaluator;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.util.Localizer;
import forge.screens.workshop.cardcreator.CardEditorSession;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;

import java.awt.BorderLayout;
import javax.swing.border.EmptyBorder;

/** Displays the game-free point breakdown for the current Card Creator draft. */
public enum VCardEvaluation implements IVDoc<forge.screens.workshop.controllers.CCardDesigner> {
    SINGLETON_INSTANCE;

    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessageorUseDefault("lblCardEvaluation", "Card Evaluation"));
    private final FSkin.SkinnedTextArea text = new FSkin.SkinnedTextArea();
    private final FScrollPane scroll = new FScrollPane(text, true);

    VCardEvaluation() {
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        text.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2));
        text.setCaretColor(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        text.setFont(FSkin.getFont());
        text.setOpaque(true);
        text.setBorder(new EmptyBorder(3, 5, 3, 4));
    }

    @Override public EDocID getDocumentID() { return EDocID.WORKSHOP_CARDEVALUATION; }
    @Override public DragTab getTabLabel() { return tab; }
    @Override public forge.screens.workshop.controllers.CCardDesigner getLayoutControl() {
        return forge.screens.workshop.controllers.CCardDesigner.SINGLETON_INSTANCE;
    }
    @Override public void setParentCell(final DragCell cell) { parentCell = cell; }
    @Override public DragCell getParentCell() { return parentCell; }

    @Override
    public void populate() {
        parentCell.getBody().setLayout(new BorderLayout());
        parentCell.getBody().setOpaque(false);
        parentCell.getBody().add(scroll, BorderLayout.CENTER);
    }

    public void loadSession(final CardEditorSession session) {
        final CardDefinitionValueEvaluator.Evaluation evaluation = session.getEvaluation();
        if (evaluation == null) {
            text.setText(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSelectCard", "Select a card or create a new card to see its evaluation."));
            return;
        }
        final StringBuilder output = new StringBuilder();
        output.append("Gross point value: ").append(evaluation.grossPointValue()).append('\n');
        output.append("Battlefield value: ").append(evaluation.battlefieldValue()).append("\n\n");
        output.append("Breakdown\n");
        for (final CardDefinitionValueEvaluator.Contribution contribution : evaluation.contributions()) {
            output.append("  ").append(contribution.label()).append(": ")
                    .append(formatSigned(contribution.value())).append('\n');
        }
        output.append("\nCasting investment\n");
        output.append("  One card: -").append(evaluation.cardOpportunityCost()).append('\n');
        output.append("  Mana: -").append(evaluation.manaInvestment()).append('\n');
        output.append("\nNet rate: ").append(evaluation.netRate()).append("\n\n");
        if (evaluation.warnings().isEmpty()) {
            output.append("Evaluation coverage: complete for the initial creature definition model.");
        } else {
            output.append("Evaluation coverage: partial\n");
            for (final String warning : evaluation.warnings()) output.append("  ").append(warning).append('\n');
        }
        output.append("\nThis is a context-free design heuristic, not a format power ranking.");
        text.setText(output.toString());
        text.setCaretPosition(0);
    }

    private static String formatSigned(final int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }
}
