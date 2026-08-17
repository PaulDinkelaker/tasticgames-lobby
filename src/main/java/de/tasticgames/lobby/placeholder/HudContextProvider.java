package de.tasticgames.lobby.placeholder;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.client.dto.network.ServerTypeResponse;
import de.tasticgames.lobby.cookie.CookieModule;
import de.tasticgames.lobby.cookie.CookieSession;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeDefinition;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cosmetic.CosmeticCategory;
import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.gateway.GatewayService;
import de.tasticgames.lobby.integration.rank.RankProvider;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.item.LobbyItemType;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.lobby.visibility.PlayerVisibilityService;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.settings.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Context-aware HUD lines (native boss-bar HUD, also exposed as placeholders): depending on the lobby item
 * the player holds, the same placeholders ({@code %tastic_ctx_*%}) describe Cookie Clicker,
 * Social, Gateway, Profile, Cosmetics, Settings, Visibility or the plain lobby. Values are plain
 * text (no colours) and localized in the player's language.
 */
public final class HudContextProvider {

    public enum Context { LOBBY, COOKIE, SOCIAL, GATEWAY, PROFILE, COSMETICS, SETTINGS, VISIBILITY }

    private final TasticCoreApi coreApi;
    private final LobbyMessages messages;
    private final LobbyItemService items;
    private final LobbyPlayerService players;
    private final RankProvider ranks;
    private final SocialSnapshotService social;
    private final GatewayService gateway;
    private final CosmeticService cosmetics;
    private final PlayerVisibilityService visibility;
    private final CookieModule cookie;
    private final Function<Player, String> playtime;
    private final Function<Player, String> sessionPlaytime;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();

    public HudContextProvider(TasticCoreApi coreApi, LobbyMessages messages, LobbyItemService items, LobbyPlayerService players, RankProvider ranks,
                              SocialSnapshotService social, GatewayService gateway, CosmeticService cosmetics, PlayerVisibilityService visibility,
                              CookieModule cookie, Function<Player, String> playtime, Function<Player, String> sessionPlaytime) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.messages = Objects.requireNonNull(messages);
        this.items = Objects.requireNonNull(items);
        this.players = Objects.requireNonNull(players);
        this.ranks = Objects.requireNonNull(ranks);
        this.social = Objects.requireNonNull(social);
        this.gateway = Objects.requireNonNull(gateway);
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.visibility = Objects.requireNonNull(visibility);
        this.cookie = Objects.requireNonNull(cookie);
        this.playtime = Objects.requireNonNull(playtime);
        this.sessionPlaytime = Objects.requireNonNull(sessionPlaytime);
    }

    /** Registers ctx, ctx_title, ctx_label_1..4, ctx_value_1..4, ctx_hint, ctx_objective. */
    public void registerInto(LobbyPlaceholders placeholders) {
        placeholders.add("ctx", p -> contextOf(p).name());
        placeholders.add("ctx_title", p -> text(p, "title"));
        for (int i = 1; i <= 4; i++) {
            final int index = i;
            placeholders.add("ctx_label_" + i, p -> text(p, "label." + index));
            placeholders.add("ctx_value_" + i, p -> value(p, index));
        }
        placeholders.add("ctx_hint", p -> text(p, "hint"));
        placeholders.add("ctx_objective", this::objective);
    }

    public Context contextOf(Player player) {
        LobbyItemType type = items.typeOf(player.getInventory().getItemInMainHand()).orElse(null);
        if (type == null) {
            return players.find(player.getUniqueId()).map(lp -> lp.inCookieWorld() ? Context.COOKIE : Context.LOBBY).orElse(Context.LOBBY);
        }
        return switch (type) {
            case COOKIE -> Context.COOKIE;
            case SOCIAL -> Context.SOCIAL;
            case GATEWAY -> Context.GATEWAY;
            case PROFILE -> Context.PROFILE;
            case COSMETICS -> Context.COSMETICS;
            case SETTINGS -> Context.SETTINGS;
            case VISIBILITY -> Context.VISIBILITY;
        };
    }

    public String title(Player player) {
        return text(player, "title");
    }

    public String label(Player player, int index) {
        return text(player, "label." + index);
    }

    public String hint(Player player) {
        return text(player, "hint");
    }

    /** Semantic icon key of the context (mapped to ItemsAdder font images in hud.yml). */
    public String contextIcon(Context context) {
        return switch (context) {
            case COOKIE -> "cookie";
            case SOCIAL -> "social";
            case GATEWAY -> "gateway";
            case PROFILE -> "profile";
            case COSMETICS -> "cosmetics";
            case SETTINGS -> "settings";
            case VISIBILITY -> "visibility";
            default -> "lobby";
        };
    }

    /** Semantic icon key of value slot 1..4 in the given context. */
    public String valueIcon(Context context, int index) {
        return switch (context) {
            case COOKIE -> switch (index) { case 1 -> "cookies"; case 2 -> "cps"; case 3 -> "prestige"; default -> "generators"; };
            case SOCIAL -> switch (index) { case 1 -> "friends"; case 2 -> "party"; case 3 -> "clan"; default -> "requests"; };
            case GATEWAY -> switch (index) { case 1 -> "survival"; case 2 -> "modes"; case 3 -> "lobby"; default -> "party"; };
            case PROFILE -> switch (index) { case 1 -> "rank"; case 2 -> "playtime"; case 3 -> "kills"; default -> "deaths"; };
            case COSMETICS -> switch (index) { case 1 -> "hat"; case 2 -> "aura"; case 3 -> "trail"; default -> "title"; };
            case SETTINGS -> switch (index) { case 1 -> "language"; case 2 -> "visibility"; case 3 -> "music"; default -> "sounds"; };
            case VISIBILITY -> switch (index) { case 1 -> "visibility"; case 2 -> "friends"; case 3 -> "party"; default -> "online"; };
            default -> switch (index) { case 1 -> "cookies"; case 2 -> "prestige"; case 3 -> "friends"; default -> "party"; };
        };
    }

    private String text(Player player, String suffix) {
        Context context = contextOf(player);
        SupportedLanguage lang = messages.languageOf(player);
        String key = "hud.ctx." + context.name().toLowerCase(Locale.ROOT) + "." + suffix;
        return messages.contains(key) ? messages.raw(lang, key).replace("<player>", player.getName()) : "";
    }

    public String value(Player player, int index) {
        SupportedLanguage lang = messages.languageOf(player);
        Locale locale = lang == SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
        Context context = contextOf(player);
        SocialSnapshotService.Snapshot snapshot = social.cached(player.getUniqueId()).orElse(null);
        String dash = "-";
        switch (context) {
            case COOKIE -> {
                CookieSession session = cookie.runtime().session(player.getUniqueId()).orElse(null);
                if (session == null) return dash;
                var profile = session.profile();
                return switch (index) {
                    case 1 -> formatter.format(profile.cookies(), locale);
                    case 2 -> formatter.formatRate(cookie.engine().compute(profile).effectiveCps(), locale);
                    case 3 -> String.valueOf(profile.prestigeLevel());
                    case 4 -> String.valueOf(profile.totalGenerators());
                    default -> dash;
                };
            }
            case SOCIAL -> {
                if (snapshot == null) return dash;
                return switch (index) {
                    case 1 -> snapshot.friendUuids().stream().filter(snapshot::online).count() + "/" + snapshot.friendUuids().size();
                    case 2 -> snapshot.party() == null ? "0" : String.valueOf(snapshot.party().members().size());
                    case 3 -> snapshot.clan() == null ? dash : snapshot.clan().name();
                    case 4 -> snapshot.friends() == null ? "0" : String.valueOf(snapshot.friends().incomingRequests().size());
                    default -> dash;
                };
            }
            case GATEWAY -> {
                GatewayService.ModeStatus survival = gateway.status(ServerTypeResponse.SURVIVAL);
                return switch (index) {
                    case 1 -> survival == null ? dash : survival.available() ? String.valueOf(survival.players()) : messages.raw(lang, "hud.ctx.gateway.offline");
                    case 2 -> String.valueOf(gateway.modes().stream().filter(GatewayService.ModeStatus::available).count());
                    case 3 -> String.valueOf(Bukkit.getOnlinePlayers().size());
                    case 4 -> snapshot == null || snapshot.party() == null ? "0" : String.valueOf(snapshot.party().members().size());
                    default -> dash;
                };
            }
            case PROFILE -> {
                return switch (index) {
                    case 1 -> ranks.rank(player).displayName();
                    case 2 -> playtime.apply(player);
                    case 3 -> String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS));
                    case 4 -> String.valueOf(player.getStatistic(Statistic.DEATHS));
                    default -> dash;
                };
            }
            case COSMETICS -> {
                CosmeticCategory category = switch (index) {
                    case 1 -> CosmeticCategory.HAT;
                    case 2 -> CosmeticCategory.AURA;
                    case 3 -> CosmeticCategory.TRAIL;
                    default -> CosmeticCategory.TITLE;
                };
                return cosmetics.equipped(player.getUniqueId(), category)
                        .map(d -> messages.contains(d.nameKey()) ? plain(messages.raw(lang, d.nameKey())) : d.id())
                        .orElse(dash);
            }
            case SETTINGS -> {
                var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
                return switch (index) {
                    case 1 -> lang.displayName();
                    case 2 -> messages.raw(lang, "lobby.visibility." + visibility.modeOf(player).name().toLowerCase(Locale.ROOT)).replaceAll("<[^>]+>", "");
                    case 3 -> tastic == null ? dash : onOff(lang, tastic.settings().get(CoreSettings.MUSIC_ENABLED));
                    case 4 -> tastic == null ? dash : onOff(lang, tastic.settings().get(CoreSettings.SOUNDS_ENABLED));
                    default -> dash;
                };
            }
            case VISIBILITY -> {
                return switch (index) {
                    case 1 -> plain(messages.raw(lang, "lobby.visibility." + visibility.modeOf(player).name().toLowerCase(Locale.ROOT)));
                    case 2 -> snapshot == null ? dash : String.valueOf(snapshot.friendUuids().stream().filter(snapshot::online).count());
                    case 3 -> snapshot == null || snapshot.party() == null ? "0" : String.valueOf(snapshot.party().members().size());
                    case 4 -> String.valueOf(Bukkit.getOnlinePlayers().size());
                    default -> dash;
                };
            }
            default -> {
                CookieSession session = cookie.runtime().session(player.getUniqueId()).orElse(null);
                return switch (index) {
                    case 1 -> session == null ? dash : formatter.format(session.profile().cookies(), locale);
                    case 2 -> session == null ? dash : String.valueOf(session.profile().prestigeLevel());
                    case 3 -> snapshot == null ? dash : snapshot.friendUuids().stream().filter(snapshot::online).count() + "/" + snapshot.friendUuids().size();
                    case 4 -> snapshot == null || snapshot.party() == null ? "0" : String.valueOf(snapshot.party().members().size());
                    default -> dash;
                };
            }
        }
    }

    public String objective(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        Locale locale = lang == SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
        Context context = contextOf(player);
        SocialSnapshotService.Snapshot snapshot = social.cached(player.getUniqueId()).orElse(null);
        switch (context) {
            case COOKIE -> {
                CookieSession session = cookie.runtime().session(player.getUniqueId()).orElse(null);
                if (session == null) return messages.raw(lang, "hud.ctx.cookie.objective.loading");
                var profile = session.profile();
                if (!session.productionActive() && profile.totalGenerators() > 0) {
                    return messages.raw(lang, "hud.ctx.cookie.objective.zone");
                }
                int next = profile.prestigeLevel() + 1;
                Optional<PrestigeDefinition> definition = cookie.engine().catalog().prestige(next);
                if (definition.isEmpty()) {
                    return messages.raw(lang, "hud.ctx.cookie.objective.max");
                }
                BigDecimal required = definition.get().requiredLifetimeCookies();
                BigDecimal lifetime = profile.lifetimeCookies().toBigDecimal();
                BigDecimal percent = required.signum() <= 0 ? BigDecimal.valueOf(100)
                        : lifetime.multiply(BigDecimal.valueOf(100)).divide(required, 1, RoundingMode.DOWN).min(BigDecimal.valueOf(100));
                return messages.raw(lang, "hud.ctx.cookie.objective.next")
                        .replace("<prestige>", String.valueOf(next))
                        .replace("<percent>", percent.toPlainString())
                        .replace("<required>", formatter.format(de.tasticgames.lobby.cookie.domain.model.CookieAmount.of(required), locale));
            }
            case SOCIAL -> {
                if (snapshot == null) return messages.raw(lang, "hud.ctx.social.objective.loading");
                int requests = snapshot.friends() == null ? 0 : snapshot.friends().incomingRequests().size();
                if (requests > 0) return messages.raw(lang, "hud.ctx.social.objective.requests").replace("<count>", String.valueOf(requests));
                if (snapshot.party() != null) return messages.raw(lang, "hud.ctx.social.objective.party").replace("<size>", String.valueOf(snapshot.party().members().size()));
                return messages.raw(lang, "hud.ctx.social.objective.invite");
            }
            case GATEWAY -> {
                if (gateway.networkMaintenance()) return messages.raw(lang, "hud.ctx.gateway.objective.maintenance");
                GatewayService.ModeStatus survival = gateway.status(ServerTypeResponse.SURVIVAL);
                if (survival == null || !survival.available()) return messages.raw(lang, "hud.ctx.gateway.objective.soon");
                return messages.raw(lang, "hud.ctx.gateway.objective.survival").replace("<players>", String.valueOf(survival.players()));
            }
            case COSMETICS -> {
                var owned = cosmetics.cached(player.getUniqueId()).orElse(null);
                long total = cosmetics.catalog().size();
                long unlocked = owned == null ? 0 : cosmetics.catalog().all().stream().filter(owned::owns).count();
                return messages.raw(lang, "hud.ctx.cosmetics.objective").replace("<unlocked>", String.valueOf(unlocked)).replace("<total>", String.valueOf(total));
            }
            case PROFILE -> {
                return cookie.runtime().session(player.getUniqueId())
                        .map(s -> messages.raw(lang, "hud.ctx.profile.objective").replace("<prestige>", String.valueOf(s.profile().prestigeLevel()))
                                .replace("<lifetime>", formatter.format(s.profile().lifetimeCookies(), locale)))
                        .orElse(messages.raw(lang, "hud.ctx.profile.objective.session").replace("<session>", sessionPlaytime.apply(player)));
            }
            case SETTINGS -> {
                return messages.raw(lang, "hud.ctx.settings.objective");
            }
            case VISIBILITY -> {
                return messages.raw(lang, "hud.ctx.visibility.objective");
            }
            default -> {
                Instant joined = players.find(player.getUniqueId()).map(lp -> lp.joinedAt()).orElse(Instant.now());
                boolean fresh = Duration.between(joined, Instant.now()).toMinutes() < 2;
                return messages.raw(lang, fresh ? "hud.ctx.lobby.objective.welcome" : "hud.ctx.lobby.objective.session")
                        .replace("<player>", player.getName()).replace("<session>", sessionPlaytime.apply(player));
            }
        }
    }

    private String onOff(SupportedLanguage lang, boolean value) {
        return messages.raw(lang, value ? "hud.on" : "hud.off");
    }

    private static String plain(String miniMessage) {
        return miniMessage.replaceAll("<[^>]+>", "").trim();
    }
}
