package fi.alavesa.scpmobs;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The blink meter. Every player's meter drains over 20 seconds, shown on the
 * XP bar (this plugin owns the XP display in blink-enabled mode). When it
 * empties, the screen is covered completely for half a second - a custom font
 * glyph in a title, shipped in the scp_and_chemistry resource pack - and the
 * meter refills. While a player is mid-blink, SCP-173 considers them blind.
 */
public final class BlinkManager {

    public static final int METER_TICKS = 20 * 20;   // 20 seconds
    public static final int BLINK_TICKS = 10;        // half a second of darkness

    private static final Component BLINK_GLYPH =
        Component.text("\uE000").font(Key.key("scp", "blink"));
    private static final Title BLINK_TITLE = Title.title(
        BLINK_GLYPH, Component.empty(),
        Title.Times.times(Duration.ZERO, Duration.ofMillis(450), Duration.ofMillis(100)));

    private final Map<UUID, Integer> meter = new HashMap<>();
    private final Map<UUID, Integer> blinkingUntil = new HashMap<>();
    private int now;
    private boolean enabled = true;

    /** Scoreboard channel the Labra HUD reads to draw the blink bar (0-100, unset = off). */
    public static final String BLINK_OBJECTIVE = "lab.blink";

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            meter.clear();
            blinkingUntil.clear();
            // pull the bar off everyone's HUD
            Objective obj = blinkObjective();
            if (obj != null) {
                for (Player p : Bukkit.getOnlinePlayers()) obj.getScoreboard().resetScores(p.getName());
            }
        }
    }

    private Objective blinkObjective() {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = board.getObjective(BLINK_OBJECTIVE);
        if (obj == null) {
            try {
                obj = board.registerNewObjective(BLINK_OBJECTIVE, Criteria.DUMMY, Component.text("Blink"));
            } catch (IllegalArgumentException e) {
                obj = board.getObjective(BLINK_OBJECTIVE);   // registered in a race
            }
        }
        return obj;
    }

    public boolean isEnabled() { return enabled; }

    /** Is this player currently mid-blink (screen covered)? */
    public boolean isBlinking(Player player) {
        Integer until = blinkingUntil.get(player.getUniqueId());
        return until != null && until > now;
    }

    /** Called every tick from the plugin's main task. */
    public void tick() {
        now++;
        if (!enabled) return;
        Objective obj = blinkObjective();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SURVIVAL
                && player.getGameMode() != GameMode.ADVENTURE) {
                if (obj != null) obj.getScoreboard().resetScores(player.getName());
                continue;
            }
            int left = meter.getOrDefault(player.getUniqueId(), METER_TICKS) - 1;
            if (left <= 0) {
                blink(player);
                left = METER_TICKS;
            }
            meter.put(player.getUniqueId(), left);
            // Publish 0-100 to the shared scoreboard; the Labra HUD draws the bar
            // at the top of the screen. The XP bar is no longer touched.
            if (obj != null) {
                int pct = Math.round(Math.max(0f, Math.min(1f, left / (float) METER_TICKS)) * 100f);
                obj.getScore(player.getName()).setScore(pct);
            }
        }
    }

    private void blink(Player player) {
        blinkingUntil.put(player.getUniqueId(), now + BLINK_TICKS);
        player.showTitle(BLINK_TITLE);
    }

    public void forget(UUID player) {
        meter.remove(player);
        blinkingUntil.remove(player);
    }
}
