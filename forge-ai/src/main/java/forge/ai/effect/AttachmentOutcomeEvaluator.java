package forge.ai.effect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Iterables;

import forge.game.GameEntity;
import forge.game.StaticEffect;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.keyword.Keyword;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

/** Values supported Aura and Equipment attachment-state changes. */
final class AttachmentOutcomeEvaluator implements OutcomeEvaluator {
    static final AttachmentOutcomeEvaluator INSTANCE = new AttachmentOutcomeEvaluator();

    // TODO(effect analysis): Support Fortifications, attachments to players and other non-card
    // entities, Bestow and Reconfigure state changes, entering-the-battlefield Aura spells,
    // multiple attachments and targets, choice-based forms, attachment replacements/triggers,
    // non-continuous restrictions such as Pacifism, downstream changes beyond the attachment and
    // its old/new recipients, optional/control-flow forms, and subability chains. Detached Auras
    // are valued as leaving the battlefield, but death/zone-change triggers, replacements, and
    // value in the graveyard are not yet included.

    private AttachmentOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || !isSupportedApi(outcome.getApi())
                || outcome.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || outcome.hasParam("Choices") || outcome.hasParam("PlayerChoices")
                || outcome.hasParam("Chooser") || outcome.hasParam("RememberAttached")
                || (!outcome.usesTargeting() && !outcome.hasParam("Defined"))) {
            return false;
        }
        return !outcome.usesTargeting()
                || AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            return outcome.getApi() == ApiType.Attach
                    ? evaluateAttach(outcome, context) : evaluateUnattach(outcome, context);
        } catch (final RuntimeException ignored) {
            // Dynamic, illegal, or malformed attachment changes contribute no outcome value.
            return 0;
        }
    }

    private static int evaluateAttach(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        final Card attachment = resolveAttachment(outcome);
        if (!isSupportedAttachment(attachment)) {
            return 0;
        }

        final AffectedCardResolver.Resolution resolution;
        if (outcome.usesTargeting()) {
            resolution = AffectedCardResolver.targeted(outcome, context,
                    target -> canAttach(attachment, target, outcome));
        } else {
            resolution = AffectedCardResolver.defined(outcome, context,
                    target -> canAttach(attachment, target, outcome));
            if (resolution.cards().size() != 1) {
                return 0;
            }
        }
        return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                target -> evaluateAttachmentChange(context, attachment, target, false));
    }

    private static int evaluateUnattach(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                ? AffectedCardResolver.targeted(outcome, context,
                        AttachmentOutcomeEvaluator::canUnattach)
                : AffectedCardResolver.defined(outcome, context,
                        AttachmentOutcomeEvaluator::canUnattach);
        return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                attachment -> evaluateAttachmentChange(context, attachment, null, true));
    }

    private static Card resolveAttachment(final SpellAbility outcome) {
        if (!outcome.hasParam("Object")) {
            return outcome.getHostCard();
        }
        final CardCollection attachments = AbilityUtils.getDefinedCards(outcome.getHostCard(),
                outcome.getParam("Object"), outcome);
        return attachments.size() == 1 ? attachments.get(0) : null;
    }

    private static boolean canAttach(final Card attachment, final Card target,
            final SpellAbility outcome) {
        return target.isInPlay() && !target.isPhasedOut()
                && attachment.getEntityAttachedTo() != target
                && target.canBeAttached(attachment, outcome);
    }

    private static boolean canUnattach(final Card attachment) {
        return isSupportedAttachment(attachment) && attachment.isAttachedToEntity()
                && attachment.getEntityAttachedTo() instanceof Card;
    }

    private static boolean isSupportedAttachment(final Card attachment) {
        return attachment != null && attachment.isInPlay() && !attachment.isPhasedOut()
                && (attachment.isAura() || attachment.isEquipment())
                && !attachment.isBestowed() && !attachment.hasKeyword(Keyword.RECONFIGURE)
                && (!attachment.isAttachedToEntity()
                        || attachment.getEntityAttachedTo() instanceof Card);
    }

    private static int evaluateAttachmentChange(final OutcomeEvaluationContext context,
            final Card attachment, final Card newTarget, final boolean detaching) {
        final Map<Integer, Card> beforeCache = new HashMap<>();
        final Card attachmentBefore = copyForAnalysis(attachment, beforeCache, false);
        final Card oldTarget = attachment.getAttachedTo();
        final Card oldTargetBefore = oldTarget == null
                ? null : copyForAnalysis(oldTarget, beforeCache, false);
        final Card newTargetBefore = newTarget == null
                ? null : copyForAnalysis(newTarget, beforeCache, false);

        final Map<Integer, Card> copyCache = new HashMap<>();
        final Card attachmentCopy = copyForAnalysis(attachment, copyCache, true);
        final Card oldTargetCopy = oldTarget == null
                ? null : copyForAnalysis(oldTarget, copyCache, true);
        final Card newTargetCopy = newTarget == null
                ? null : copyForAnalysis(newTarget, copyCache, true);

        final GameEntity copiedOldTarget = attachmentCopy.getEntityAttachedTo();
        if (copiedOldTarget != null) {
            copiedOldTarget.removeAttachedCard(attachmentCopy);
            attachmentCopy.setEntityAttachedTo(null);
        }
        if (newTargetCopy != null) {
            attachmentCopy.setEntityAttachedTo(newTargetCopy);
            newTargetCopy.addAttachedCard(attachmentCopy);
        }

        final CardCollection preList = new CardCollection(attachmentCopy);
        if (oldTargetCopy != null) {
            preList.add(oldTargetCopy);
        }
        if (newTargetCopy != null && newTargetCopy != oldTargetCopy) {
            preList.add(newTargetCopy);
        }
        final Set<Card> affectedCopies = new HashSet<>(preList);

        try {
            attachment.getGame().getAction().checkStaticAbilities(
                    false, affectedCopies, preList);
            final Map<Card, Card> changes = new LinkedHashMap<>();
            changes.put(attachmentBefore,
                    detaching && attachment.isAura() ? null : attachmentCopy);
            if (oldTarget != null) {
                changes.put(oldTargetBefore, oldTargetCopy);
            }
            if (newTarget != null) {
                changes.put(newTargetBefore, newTargetCopy);
            }
            return CardStateDeltaEvaluator.evaluateBoardChanges(context, changes);
        } finally {
            attachment.getGame().getAction().checkStaticAbilities(false);
        }
    }

    private static Card copyForAnalysis(final Card original,
            final Map<Integer, Card> copyCache, final boolean stripCurrentStaticChanges) {
        final Card copy = CardCopyService.getLKICopy(original, copyCache);
        if (original.getZone() != null) {
            copy.setZone(original.getZone());
        }
        if (stripCurrentStaticChanges) {
            removeCurrentStaticChanges(original, copy);
        }
        return copy;
    }

    private static void removeCurrentStaticChanges(final Card original, final Card copy) {
        for (final Card source : original.getGame().getCardsIn(ZoneType.Battlefield)) {
            for (final StaticAbility ability : Iterables.concat(
                    source.getStaticAbilities(), source.getHiddenStaticAbilities())) {
                final StaticEffect effect = original.getGame().getStaticEffects()
                        .getStaticEffect(ability);
                if (effect.getAffectedCards().contains(original)) {
                    StaticAbilityAnalyzer.removeTrackedChanges(copy, effect, ability);
                }
            }
        }
    }

    private static boolean isSupportedApi(final ApiType api) {
        return api == ApiType.Attach || api == ApiType.Unattach;
    }
}
