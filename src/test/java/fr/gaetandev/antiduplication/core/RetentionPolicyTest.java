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
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention decision is the one place where the plugin chooses what to
 * destroy, so every branch of it is pinned down here.
 */
class RetentionPolicyTest {

    private static final String UUID_TAG = "TEST_12345678";
    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CART = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static TrackedItem playerCopy(final UUID playerId, final String name, final int slot) {
        return copy(ItemLocation.ofPlayer(ItemLocation.Kind.PLAYER_INVENTORY, playerId, name, slot));
    }

    private static TrackedItem containerCopy(final int slot) {
        return copy(ItemLocation.ofContainer("CHEST", null, slot));
    }

    private static TrackedItem groundCopy() {
        return copy(ItemLocation.ofGround(CART, null));
    }

    private static TrackedItem entityCopy(final int slot) {
        return copy(ItemLocation.ofEntity(
                ItemLocation.Kind.ENTITY_CONTAINER, CART, "MINECART_CHEST", null, slot));
    }

    private static TrackedItem copy(final ItemLocation location) {
        return new TrackedItem.Builder()
                .uuid(UUID_TAG)
                .material(Material.DIAMOND_SWORD)
                .location(location)
                .build();
    }

    @Test
    @DisplayName("The holder the UUID was last seen at keeps the item")
    void testKnownOwnerWins() {
        final TrackedItem alice = playerCopy(ALICE, "Alice", 5);
        final TrackedItem bob = playerCopy(BOB, "Bob", 1);
        final List<TrackedItem> copies = Arrays.asList(bob, alice);

        final String aliceKey = alice.getItemLocation().getOwnerKey();

        assertThat(RetentionPolicy.chooseRetained(aliceKey, copies)).isSameAs(alice);
        assertThat(RetentionPolicy.chooseRetained(bob.getItemLocation().getOwnerKey(), copies)).isSameAs(bob);
    }

    @Test
    @DisplayName("An owner that no longer holds a copy falls back to the ordering")
    void testStaleKnownOwner() {
        final TrackedItem alice = playerCopy(ALICE, "Alice", 5);
        final TrackedItem chest = containerCopy(2);

        final TrackedItem retained = RetentionPolicy.chooseRetained(
                "PLAYER_INVENTORY:" + UUID.randomUUID(), Arrays.asList(chest, alice));

        assertThat(retained).isSameAs(alice);
    }

    @Test
    @DisplayName("With no history, the strongest holder keeps the item")
    void testHolderRanking() {
        final TrackedItem player = playerCopy(ALICE, "Alice", 5);
        final TrackedItem chest = containerCopy(2);
        final TrackedItem cart = entityCopy(1);
        final TrackedItem ground = groundCopy();

        // Whatever order the sweep visited them in, the player copy survives.
        assertThat(RetentionPolicy.chooseRetained(null, Arrays.asList(ground, cart, chest, player)))
                .isSameAs(player);
        assertThat(RetentionPolicy.chooseRetained(null, Arrays.asList(player, chest, cart, ground)))
                .isSameAs(player);
        assertThat(RetentionPolicy.chooseRetained(null, Arrays.asList(ground, cart, chest)))
                .isSameAs(chest);
        assertThat(RetentionPolicy.chooseRetained(null, Arrays.asList(ground, cart)))
                .isSameAs(cart);
    }

    @Test
    @DisplayName("Two copies in one inventory resolve to the lowest slot, whatever the order")
    void testDeterministicWithinOneHolder() {
        final TrackedItem low = playerCopy(ALICE, "Alice", 2);
        final TrackedItem high = playerCopy(ALICE, "Alice", 30);

        assertThat(RetentionPolicy.chooseRetained(null, Arrays.asList(high, low))).isSameAs(low);
        assertThat(RetentionPolicy.chooseRetained(null, Arrays.asList(low, high))).isSameAs(low);
    }

    @Test
    @DisplayName("A single copy is returned as is")
    void testSingleCopy() {
        final TrackedItem only = containerCopy(0);
        assertThat(RetentionPolicy.chooseRetained(null, Collections.singletonList(only))).isSameAs(only);
    }

    @Test
    @DisplayName("Holder ranks are unique, so the ordering can never be ambiguous")
    void testRetentionRanksAreDistinct() {
        final List<Integer> ranks = new ArrayList<>();
        for (final ItemLocation.Kind kind : ItemLocation.Kind.values()) {
            ranks.add(kind.getRetentionRank());
        }
        assertThat(ranks).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @DisplayName("Severity rises with the copy count and with the value of the item")
    @CsvSource({
            "2, false, LOW",
            "2, true, LOW",
            "3, false, MEDIUM",
            "3, true, HIGH",
            "4, true, HIGH",
            "5, false, HIGH",
            "5, true, CRITICAL",
            "9, true, CRITICAL",
            "10, false, CRITICAL"
    })
    void testSeverity(final int copies, final boolean valuable, final String expected) {
        assertThat(RetentionPolicy.severityOf(copies, valuable))
                .isEqualTo(DuplicationEvent.Severity.valueOf(expected));
    }

    @Test
    @DisplayName("The spread summary names one player, or counts several")
    void testDescribeSpread() {
        assertThat(RetentionPolicy.describeSpread(Arrays.asList(
                playerCopy(ALICE, "Alice", 1), playerCopy(ALICE, "Alice", 2))))
                .isEqualTo("All player-held copies belong to Alice.");

        assertThat(RetentionPolicy.describeSpread(Arrays.asList(
                playerCopy(ALICE, "Alice", 1), playerCopy(BOB, "Bob", 2))))
                .isEqualTo("Copies spread across 2 players.");

        assertThat(RetentionPolicy.describeSpread(Arrays.asList(
                playerCopy(ALICE, "Alice", 1), containerCopy(3))))
                .isEqualTo("All player-held copies belong to Alice. Also present in [CHEST].");

        assertThat(RetentionPolicy.describeSpread(Arrays.asList(containerCopy(1), groundCopy())))
                .isEqualTo("Also present in [CHEST, GROUND].");
    }
}
