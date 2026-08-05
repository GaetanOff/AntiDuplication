/*
 * AntiDuplication: a plugin for Minecraft servers.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fr.gaetandev.antiduplication.core;

import fr.gaetandev.antiduplication.model.DuplicationEvent;
import fr.gaetandev.antiduplication.model.ItemLocation;
import fr.gaetandev.antiduplication.model.TrackedItem;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which copy of a duplicated item survives, and how bad the duplication
 * is.
 *
 * <p>Split out of the detector because it is the most consequential decision the
 * plugin makes — every other copy gets deleted — and because keeping it free of
 * any server dependency is what makes it testable.
 */
final class RetentionPolicy {

    /**
     * Fallback ordering when nothing is known about the item's history. Prefers
     * the holder with the strongest claim (a player over a chest, a chest over
     * the ground), then orders by holder and slot so the outcome does not depend
     * on which order the sweep happened to visit things in.
     */
    private static final Comparator<TrackedItem> RETENTION_ORDER = new Comparator<TrackedItem>() {
        @Override
        public int compare(final TrackedItem left, final TrackedItem right) {
            final ItemLocation leftLocation = left.getItemLocation();
            final ItemLocation rightLocation = right.getItemLocation();

            final int byKind = Integer.compare(leftLocation.getKind().getRetentionRank(),
                    rightLocation.getKind().getRetentionRank());
            if (byKind != 0) {
                return byKind;
            }
            final int byOwner = leftLocation.getOwnerKey().compareTo(rightLocation.getOwnerKey());
            return byOwner != 0 ? byOwner : Integer.compare(leftLocation.getSlot(), rightLocation.getSlot());
        }
    };

    private RetentionPolicy() {
    }

    /**
     * Picks the copy to keep.
     *
     * <p>The holder the UUID was last seen at wins: that keeps the original in
     * place and deletes whatever appeared elsewhere. With no history, the
     * deterministic fallback ordering decides.
     *
     * @param knownOwnerKey the holder this UUID was last seen at, or {@code null}
     * @param copies        the observed copies, never empty
     */
    static TrackedItem chooseRetained(final String knownOwnerKey, final List<TrackedItem> copies) {
        if (knownOwnerKey != null) {
            for (final TrackedItem copy : copies) {
                if (knownOwnerKey.equals(copy.getItemLocation().getOwnerKey())) {
                    return copy;
                }
            }
        }
        return Collections.min(copies, RETENTION_ORDER);
    }

    /**
     * How serious a duplication is, from the number of copies and whether any of
     * them is worth duplicating in the first place.
     */
    static DuplicationEvent.Severity severityOf(final int copies, final boolean valuable) {
        if (copies >= 10 || (copies >= 5 && valuable)) {
            return DuplicationEvent.Severity.CRITICAL;
        }
        if (copies >= 5 || (copies >= 3 && valuable)) {
            return DuplicationEvent.Severity.HIGH;
        }
        return copies >= 3 ? DuplicationEvent.Severity.MEDIUM : DuplicationEvent.Severity.LOW;
    }

    /**
     * Short summary of how far a duplication has spread, which is the first thing
     * an administrator wants to know.
     */
    static String describeSpread(final Collection<TrackedItem> copies) {
        // Sorted sets so the wording is stable from one report to the next.
        final Set<String> players = new TreeSet<>();
        final Set<String> holders = new TreeSet<>();
        for (final TrackedItem copy : copies) {
            if (copy.getPlayerName() != null) {
                players.add(copy.getPlayerName());
            } else {
                holders.add(copy.getContainer());
            }
        }

        final StringBuilder info = new StringBuilder();
        if (players.size() == 1) {
            info.append("All player-held copies belong to ").append(players.iterator().next()).append(". ");
        } else if (players.size() > 1) {
            info.append("Copies spread across ").append(players.size()).append(" players. ");
        }
        if (!holders.isEmpty()) {
            info.append("Also present in ").append(holders).append('.');
        }
        return info.toString().trim();
    }
}
