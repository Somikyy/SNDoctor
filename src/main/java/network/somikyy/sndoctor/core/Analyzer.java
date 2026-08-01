/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.core;

import network.somikyy.sndoctor.core.Finding.Severity;
import network.somikyy.sndoctor.core.Finding.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Applies SNDoctor's rule set to the raw facts of one jar.
 *
 * <p><b>Rule policy.</b> Every rule in this class is backed by a primary source - a PaperMC
 * announcement, a Paper javadoc deprecation, or an observable property of the jar itself.
 * Claims that could not be verified against a primary source are deliberately absent; see
 * {@code docs/SPEC-SNDoctor.md}, section "Candidate rules". A compatibility scanner that
 * reports a change that never happened is worse than one that reports nothing.
 */
public final class Analyzer {

    /** Version epoch this rule set targets. */
    public static final String TARGET = "26.1+";

    private static final Pattern LEGACY_NMS_PACKAGE =
            Pattern.compile("^net/minecraft/server/v1_\\d+_R\\d+/.*");
    private static final Pattern VERSIONED_CRAFTBUKKIT =
            Pattern.compile("^org/bukkit/craftbukkit/v1_\\d+_R\\d+/.*");
    private static final Pattern VERSION_TOKEN =
            Pattern.compile(".*\\bv1_\\d+_R\\d+\\b.*");
    private static final Pattern RAW_IP_URL =
            Pattern.compile("^https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/.*)?$");

    /** Package prefixes of well-known libraries, used by the masquerading-class heuristic. */
    private static final String[] KNOWN_LIBRARY_PREFIXES = {
            "org/apache/commons/", "com/google/common/", "com/google/gson/", "org/slf4j/",
            "org/json/", "io/netty/", "org/yaml/snakeyaml/", "net/kyori/", "org/apache/logging/",
            "com/mojang/", "org/objectweb/asm/", "okhttp3/", "com/zaxxer/hikari/"
    };

    private static final String[] SUSPICIOUS_URL_HOSTS = {
            "pastebin.com", "hastebin.com", "paste.ee", "transfer.sh", "anonfiles.com",
            "discord.com/api/webhooks", "discordapp.com/api/webhooks", "api.telegram.org",
            "ngrok.io", "trycloudflare.com", "0x0.st", "file.io"
    };

    private static final String[] SERVER_SECRET_MARKERS = {
            "rcon.password", "ops.json", "usercache.json", "server.properties",
            "eula.txt", "whitelist.json", "banned-ips.json"
    };

    private final Map<String, String> spigotNames;

    public Analyzer(Map<String, String> spigotNames) {
        this.spigotNames = spigotNames;
    }

    // ------------------------------------------------------------- rule data

    /** Loads the bundled name table, optionally merged with a user-supplied override file. */
    public static Analyzer create(Path override) {
        Map<String, String> names = new LinkedHashMap<>();
        try (InputStream in = Analyzer.class.getResourceAsStream("/sndoctor/spigot-names.txt")) {
            if (in != null) {
                readNames(new InputStreamReader(in, StandardCharsets.UTF_8), names);
            }
        } catch (IOException ignored) {
            // bundled resource missing - the other rules still work
        }
        if (override != null && Files.isReadable(override)) {
            try (BufferedReader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                readNames(r, names);
            } catch (IOException ignored) {
                // a bad override file must not stop a scan
            }
        }
        return new Analyzer(names);
    }

    private static void readNames(java.io.Reader reader, Map<String, String> out) throws IOException {
        BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq > 0) {
                out.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
            }
        }
    }

    public int nameTableSize() {
        return spigotNames.size();
    }

    // -------------------------------------------------------------- analysis

    /**
     * Runs every rule against one jar.
     *
     * @param serverJava Java feature version of the server that will run the plugin,
     *                   or 0 when unknown (CLI mode without {@code --java}).
     */
    public List<Finding> analyze(JarFacts f, int serverJava) {
        List<Finding> out = new ArrayList<>();

        checkSpigotMappings(f, out);
        checkLegacyNmsPackage(f, out);
        checkVersionedCraftBukkit(f, out);
        checkVersionReflection(f, out);
        checkUnversionedCraftBukkit(f, out);

        checkConversationApi(f, out);
        checkMetadataApi(f, out);
        checkPlayerSpawnLocationEvent(f, out);
        checkTeleportFlag(f, out);
        checkGameModeChangeCause(f, out);
        checkLegacyMaterial(f, out);

        checkDescriptor(f, out);
        checkJavaVersion(f, serverJava, out);
        checkFolia(f, out);
        checkRuntimeLibraries(f, out);
        checkUnreadableClasses(f, out);

        checkProtocolLib(f, out);
        checkVault(f, out);

        checkRuntimeExec(f, out);
        checkDynamicClassLoading(f, out);
        checkSuspiciousUrls(f, out);
        checkServerSecrets(f, out);
        checkMasqueradingClasses(f, out);

        out.sort((a, b) -> Integer.compare(a.severity.ordinal(), b.severity.ordinal()));
        return out;
    }

    // ---- mapping rules ----------------------------------------------------

    private void checkSpigotMappings(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("nms.spigot-mappings", Severity.BLOCKER, new Text(
                "Spigot-маппинги NMS",
                "Spigot-mapped NMS names",
                "С 26.1 Mojang не отдаёт обфусцированные server-jar'ы, а Paper полностью убрал "
                        + "внутренний ремаппер. Формулировка PaperMC: плагин со Spigot-маппингами "
                        + "«не будет работать на 26.1, независимо от того, Paper у вас или нет».",
                "Since 26.1 Mojang no longer ships obfuscated server jars and Paper dropped its "
                        + "internal remapper. PaperMC: such a plugin \"will not work on 26.1, no "
                        + "matter if you are using Paper or not\".",
                "Пересобрать плагин на Mojang-маппингах (paperweight-userdev) или найти замену. "
                        + "Обновление плагина автором — самый быстрый путь.",
                "Rebuild against Mojang mappings (paperweight-userdev) or replace the plugin."));

        for (String cls : f.referencedClasses) {
            if (!cls.startsWith("net/minecraft/")) {
                continue;
            }
            String simple = simpleName(cls);
            String mojang = spigotNames.get(simple);
            if (mojang != null) {
                finding.evidence(simple + " → " + mojang);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkLegacyNmsPackage(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("nms.legacy-versioned-package", Severity.BLOCKER, new Text(
                "Версионный пакет NMS (до 1.17)",
                "Version-stamped NMS package (pre-1.17)",
                "Пакеты net.minecraft.server.v1_XX_RX исчезли ещё в 1.17. На 1.17+ такой плагин "
                        + "не загрузится вообще.",
                "net.minecraft.server.v1_XX_RX packages disappeared in 1.17. This plugin cannot "
                        + "load on 1.17+ at all.",
                "Плагин рассчитан на 1.16 и старше. Нужна принципиально новая версия или замена.",
                "Written for 1.16 or older. Needs a rewrite or a replacement."));
        for (String cls : f.referencedClasses) {
            if (LEGACY_NMS_PACKAGE.matcher(cls).matches()) {
                finding.evidence(packageOf(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkVersionedCraftBukkit(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("craftbukkit.versioned", Severity.BLOCKER, new Text(
                "Версионный пакет CraftBukkit",
                "Version-stamped CraftBukkit package",
                "Paper убрал релокацию org.bukkit.craftbukkit.vX_Y_RZ ещё в 1.20.5. Такие ссылки "
                        + "приводят к NoClassDefFoundError при загрузке.",
                "Paper removed org.bukkit.craftbukkit.vX_Y_RZ relocation in 1.20.5. These "
                        + "references throw NoClassDefFoundError on load.",
                "Заменить на неверсионные org.bukkit.craftbukkit.* или на публичный API.",
                "Use the unversioned org.bukkit.craftbukkit.* package or the public API."));
        for (String cls : f.referencedClasses) {
            if (VERSIONED_CRAFTBUKKIT.matcher(cls).matches()) {
                finding.evidence(packageOf(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkVersionReflection(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("nms.version-reflection", Severity.WARN, new Text(
                "Определение версии через vX_Y_RZ в строках",
                "Version detection via vX_Y_RZ string literals",
                "Плагин собирает имя класса из версии пакета. На 1.20.5+ релокации больше нет, "
                        + "и такой код обычно падает или молча уходит в ветку «неподдерживаемая версия».",
                "The plugin builds class names from the package version. Relocation is gone since "
                        + "1.20.5, so this usually throws or silently degrades.",
                "Убрать версионную рефлексию. Работать с неверсионным CraftBukkit или с API.",
                "Drop version-based reflection; use the unversioned package or the API."));
        for (String s : f.stringConstants) {
            if (VERSION_TOKEN.matcher(s).matches()) {
                finding.evidence(trim(s, 60));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkUnversionedCraftBukkit(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("craftbukkit.direct", Severity.INFO, new Text(
                "Прямое обращение к CraftBukkit",
                "Direct CraftBukkit usage",
                "Плагин использует внутренности сервера напрямую. Само по себе это не поломка, "
                        + "но такие места чаще всего ломаются при смене мажорной версии.",
                "The plugin touches server internals directly. Not broken by itself, but this is "
                        + "where major version bumps usually hurt.",
                "Ничего делать не нужно — просто проверь этот плагин первым после обновления.",
                "Nothing to do - just test this plugin first after an upgrade."));
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/craftbukkit/")
                    && !VERSIONED_CRAFTBUKKIT.matcher(cls).matches()) {
                finding.evidence(simpleName(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    // ---- API deprecation / removal rules ---------------------------------

    private void checkConversationApi(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("api.conversation", Severity.WARN, new Text(
                "Conversation API (deprecated for removal)",
                "Conversation API (deprecated for removal)",
                "Paper 1.21.9/1.21.10: «This API has been unmaintained and largely unused for a "
                        + "long time... Hence, we have decided to deprecate it for removal.» "
                        + "Пакет org.bukkit.conversations будет удалён.",
                "Paper 1.21.9/1.21.10: \"This API has been unmaintained and largely unused for a "
                        + "long time... Hence, we have decided to deprecate it for removal.\"",
                "Перейти на Dialog API или слушать AsyncChatEvent вручную.",
                "Migrate to the Dialog API or listen to AsyncChatEvent manually."));
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/conversations/")) {
                finding.evidence(simpleName(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkMetadataApi(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("api.metadata", Severity.WARN, new Text(
                "Metadata API (deprecated)",
                "Metadata API (deprecated)",
                "Paper 1.21.9/1.21.10: «The entity/block entity Metadatable API has been deprecated "
                        + "as it is generally inferior to the PersistentDataContainer API.» "
                        + "Исторически этот API — источник утечек памяти.",
                "Paper 1.21.9/1.21.10: \"The entity/block entity Metadatable API has been "
                        + "deprecated as it is generally inferior to the PersistentDataContainer API.\"",
                "Перейти на PersistentDataContainer.",
                "Migrate to PersistentDataContainer."));
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/metadata/")) {
                finding.evidence(simpleName(cls));
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.endsWith("#setMetadata") || ref.endsWith("#getMetadata")
                    || ref.endsWith("#removeMetadata")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkPlayerSpawnLocationEvent(JarFacts f, List<Finding> out) {
        if (f.referencedClasses.contains("org/bukkit/event/player/PlayerSpawnLocationEvent")) {
            out.add(new Finding("api.player-spawn-location-event", Severity.WARN, new Text(
                    "PlayerSpawnLocationEvent (deprecated)",
                    "PlayerSpawnLocationEvent (deprecated)",
                    "Paper 1.21.9/1.21.10: «Plugins should migrate to the "
                            + "AsyncPlayerSpawnLocationEvent as soon as possible, as listening to the "
                            + "PlayerSpawnLocationEvent has unintended side effects.» Загрузка спавна "
                            + "переехала в configuration-фазу.",
                    "Paper 1.21.9/1.21.10: \"Plugins should migrate to the "
                            + "AsyncPlayerSpawnLocationEvent as soon as possible, as listening to the "
                            + "PlayerSpawnLocationEvent has unintended side effects.\"",
                    "Перейти на AsyncPlayerSpawnLocationEvent.",
                    "Migrate to AsyncPlayerSpawnLocationEvent."))
                    .evidence("PlayerSpawnLocationEvent"));
        }
    }

    private void checkTeleportFlag(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("api.teleport-flag-entitystate", Severity.BREAKING, new Text(
                "TeleportFlag.EntityState больше не работает",
                "TeleportFlag.EntityState no longer functions",
                "Paper 1.21.9/1.21.10: «using TeleportFlag.EntityState no longer has any "
                        + "functionality». Флаг принимается, но ничего не делает — это тихая поломка, "
                        + "которую не видно в логах.",
                "Paper 1.21.9/1.21.10: \"using TeleportFlag.EntityState no longer has any "
                        + "functionality\". The flag is accepted and silently ignored.",
                "Закрывать инвентарь и высаживать пассажиров вручную перед телепортом.",
                "Call closeInventory() and eject() manually before teleporting."));
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("io/papermc/paper/entity/TeleportFlag$EntityState")) {
                finding.evidence("TeleportFlag.EntityState");
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.startsWith("io/papermc/paper/entity/TeleportFlag$EntityState#")) {
                finding.evidence(ref.substring(ref.indexOf('#') + 1));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkGameModeChangeCause(JarFacts f, List<Finding> out) {
        boolean uses = f.referencedClasses.stream()
                .anyMatch(c -> c.startsWith("org/bukkit/event/player/PlayerGameModeChangeEvent"));
        if (uses) {
            out.add(new Finding("api.gamemode-change-cause", Severity.INFO, new Text(
                    "Новая причина GAMEMODE_SWITCHER",
                    "New GAMEMODE_SWITCHER cause",
                    "Paper 1.21.9 добавил причину GAMEMODE_SWITCHER в PlayerGameModeChangeEvent, "
                            + "заменив ей COMMAND при смене режима через переключатель. Код, который "
                            + "проверяет только COMMAND, теперь пропускает часть случаев.",
                    "Paper 1.21.9 introduced GAMEMODE_SWITCHER, replacing COMMAND when a player uses "
                            + "the gamemode switcher. Code checking only COMMAND now misses cases.",
                    "Проверить, обрабатывается ли новая причина, если плагин смотрит на Cause.",
                    "Handle the new cause if the plugin inspects Cause."))
                    .evidence("PlayerGameModeChangeEvent"));
        }
    }

    private void checkLegacyMaterial(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("api.legacy-material", Severity.WARN, new Text(
                "Легаси-API предметов (до 1.13)",
                "Pre-1.13 legacy item API",
                "org.bukkit.material, Material.getId() и durability как подтип — наследие «до "
                        + "флаттенинга» 1.13. Работает нестабильно и давно депрекейтнуто.",
                "org.bukkit.material, Material.getId() and durability-as-subtype predate the 1.13 "
                        + "flattening; long deprecated and unreliable.",
                "Перейти на Material по имени, BlockData и Damageable.",
                "Use named Material, BlockData and Damageable."));
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/material/")) {
                finding.evidence(simpleName(cls));
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.equals("org/bukkit/Material#getId") || ref.equals("org/bukkit/Material#getMaterial")
                    || ref.endsWith("ItemStack#getDurability") || ref.endsWith("ItemStack#setDurability")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    // ---- descriptor / environment rules ----------------------------------

    private void checkDescriptor(JarFacts f, List<Finding> out) {
        if (!f.hasPluginYml && !f.hasPaperPluginYml) {
            out.add(new Finding("meta.not-a-plugin", Severity.INFO, new Text(
                    "Это не плагин",
                    "Not a plugin",
                    "В jar нет plugin.yml и paper-plugin.yml. Обычно это библиотека, положенная в "
                            + "plugins/ по ошибке, или файл, который туда попал случайно.",
                    "No plugin.yml or paper-plugin.yml. Usually a library dropped into plugins/ by "
                            + "mistake.",
                    "Убрать из plugins/, если это не зависимость, которую сервер грузит явно.",
                    "Remove it from plugins/ unless the server loads it explicitly."))
                    .evidence(f.fileName));
            return;
        }

        if (!f.mainClass.isEmpty()) {
            String internal = f.mainClass.replace('.', '/');
            if (!f.ownClasses.contains(internal)) {
                out.add(new Finding("meta.main-class-missing", Severity.BLOCKER, new Text(
                        "Главный класс не найден в jar",
                        "Main class missing from jar",
                        "В описании указан main, которого нет внутри архива. Сервер упадёт с "
                                + "ClassNotFoundException при загрузке.",
                        "The descriptor points at a main class that is not inside the archive. The "
                                + "server throws ClassNotFoundException on load.",
                        "Файл повреждён или собран неправильно. Скачать заново из официального источника.",
                        "Corrupt or mis-built jar. Re-download from the official source."))
                        .evidence(f.mainClass));
            }
        }

        if (f.hasPaperPluginYml) {
            out.add(new Finding("meta.paper-plugin", Severity.INFO, new Text(
                    "Paper-плагин (новый загрузчик)",
                    "Paper plugin (new loader)",
                    "Использует paper-plugin.yml и новый загрузчик Paper. Часть инструментов "
                            + "(например hot-reload) с такими плагинами работает иначе.",
                    "Uses paper-plugin.yml and Paper's new loader. Some tooling (hot reload in "
                            + "particular) behaves differently with these.",
                    "Ничего делать не нужно.",
                    "Nothing to do."))
                    .evidence("paper-plugin.yml"));
            return; // api-version rules below apply to Bukkit descriptors
        }

        if (f.apiVersion.isEmpty()) {
            out.add(new Finding("meta.no-api-version", Severity.WARN, new Text(
                    "Не указан api-version",
                    "Missing api-version",
                    "Без api-version сервер считает плагин legacy-плагином эпохи 1.12 и включает "
                            + "конвертацию материалов. На современных версиях это регулярно "
                            + "заканчивается отказом загрузки.",
                    "Without api-version the server treats the plugin as a 1.12-era legacy plugin "
                            + "and enables material conversion. On modern versions this often ends "
                            + "in a refusal to load.",
                    "Автору — добавить api-version в plugin.yml. Админу — проверить загрузку в логе.",
                    "Author: add api-version to plugin.yml. Admin: check the startup log."))
                    .evidence("plugin.yml"));
        } else {
            double v = parseApiVersion(f.apiVersion);
            if (v > 0 && v < 1.13) {
                out.add(new Finding("meta.ancient-api-version", Severity.WARN, new Text(
                        "Очень старый api-version",
                        "Very old api-version",
                        "api-version " + f.apiVersion + " — до флаттенинга 1.13. Плагин почти "
                                + "наверняка написан под другой набор материалов.",
                        "api-version " + f.apiVersion + " predates the 1.13 flattening.",
                        "Ищи обновление или замену.",
                        "Look for an update or a replacement."))
                        .evidence("api-version: " + f.apiVersion));
            }
        }
    }

    private void checkJavaVersion(JarFacts f, int serverJava, List<Finding> out) {
        int required = f.requiredJava();
        if (required <= 0) {
            return;
        }
        if (serverJava > 0 && required > serverJava) {
            out.add(new Finding("java.too-new", Severity.BLOCKER, new Text(
                    "Требует Java " + required + ", а сервер на Java " + serverJava,
                    "Requires Java " + required + " but the server runs Java " + serverJava,
                    "Плагин скомпилирован под более новую Java. Загрузка упадёт с "
                            + "UnsupportedClassVersionError.",
                    "Compiled for a newer Java. Loading fails with UnsupportedClassVersionError.",
                    "Обновить Java на сервере до " + required + " или взять сборку плагина под "
                            + "старую Java.",
                    "Upgrade the server JVM to " + required + " or use a build for an older Java."))
                    .evidence("class major " + f.maxClassMajor));
        } else {
            out.add(new Finding("java.required", Severity.INFO, new Text(
                    "Требует Java " + required,
                    "Requires Java " + required,
                    "Минимальная версия Java, на которой этот плагин запустится.",
                    "Minimum Java version this plugin can run on.",
                    "Учитывай при выборе версии сервера: Minecraft 26.2 требует Java 25.",
                    "Note that Minecraft 26.2 itself requires Java 25."))
                    .evidence("class major " + f.maxClassMajor));
        }
    }

    private void checkFolia(JarFacts f, List<Finding> out) {
        if (f.foliaSupported) {
            out.add(new Finding("folia.supported", Severity.INFO, new Text(
                    "Заявлена поддержка Folia",
                    "Declares Folia support",
                    "В описании стоит folia-supported: true.",
                    "The descriptor sets folia-supported: true.",
                    "Ничего делать не нужно.",
                    "Nothing to do."))
                    .evidence("folia-supported: true"));
        }
    }

    private void checkRuntimeLibraries(JarFacts f, List<Finding> out) {
        if (!f.libraries.isEmpty()) {
            Finding finding = new Finding("meta.runtime-libraries", Severity.INFO, new Text(
                    "Скачивает зависимости при старте",
                    "Downloads dependencies at startup",
                    "Плагин использует секцию libraries: сервер тянет эти артефакты из интернета при "
                            + "запуске. Без сети или при недоступности репозитория плагин не загрузится.",
                    "The plugin uses the libraries: section, so the server downloads artifacts at "
                            + "startup. No network means no load.",
                    "Учитывай на серверах без внешнего доступа.",
                    "Relevant on servers without outbound network access."));
            for (String lib : f.libraries) {
                finding.evidence(lib);
            }
            out.add(finding);
        }
    }

    private void checkUnreadableClasses(JarFacts f, List<Finding> out) {
        if (f.unreadableClasses > 0) {
            out.add(new Finding("scan.unreadable-classes", Severity.WARN, new Text(
                    "Часть классов не читается",
                    "Some classes could not be parsed",
                    f.unreadableClasses + " из " + f.classCount + " классов не удалось разобрать. "
                            + "Обычно это обфускация или более новый формат class-файла — отчёт по "
                            + "этому плагину неполный.",
                    f.unreadableClasses + " of " + f.classCount + " classes could not be parsed "
                            + "(obfuscation or a newer class file format). This report is incomplete.",
                    "Проверить плагин вручную. Тяжёлая обфускация в бесплатном плагине — повод "
                            + "насторожиться.",
                    "Check manually. Heavy obfuscation in a free plugin is worth a second look."))
                    .evidence(f.unreadableClasses + " class(es)"));
        }
    }

    // ---- dependency rules -------------------------------------------------

    private void checkProtocolLib(JarFacts f, List<Finding> out) {
        boolean referenced = f.referencedClasses.stream().anyMatch(c -> c.startsWith("com/comphenix/protocol/"));
        boolean declared = containsIgnoreCase(f.depend, "ProtocolLib") || containsIgnoreCase(f.softDepend, "ProtocolLib");
        if (referenced || declared) {
            Finding finding = new Finding("dep.protocollib", Severity.WARN, new Text(
                    "Зависит от ProtocolLib",
                    "Depends on ProtocolLib",
                    "ProtocolLib — самая хрупкая общая зависимость экосистемы: последний релиз 5.4.0 "
                            + "от 9 августа 2025, в трекере открыт вопрос о том, что OpenJDK планирует "
                            + "закрыть модификацию final-полей через рефлексию. Если ProtocolLib "
                            + "отвалится на новой версии, отвалятся все зависящие от него плагины.",
                    "ProtocolLib is the ecosystem's most fragile shared dependency: last release "
                            + "5.4.0 on 2025-08-09, with an open issue about OpenJDK removing final "
                            + "field reflection. If it breaks, everything depending on it breaks.",
                    "Проверить совместимость ProtocolLib с целевой версией до апгрейда. Многие "
                            + "плагины уже переехали на PacketEvents.",
                    "Verify ProtocolLib compatibility before upgrading; many plugins moved to PacketEvents."));
            finding.evidence(declared ? "объявлен в plugin.yml" : "используется в коде");
            out.add(finding);
        }
    }

    private void checkVault(JarFacts f, List<Finding> out) {
        boolean referenced = f.referencedClasses.stream().anyMatch(c -> c.startsWith("net/milkbowl/vault/"));
        boolean declared = containsIgnoreCase(f.depend, "Vault") || containsIgnoreCase(f.softDepend, "Vault");
        if (referenced || declared) {
            out.add(new Finding("dep.vault", Severity.INFO, new Text(
                    "Зависит от Vault",
                    "Depends on Vault",
                    "Vault не обновлялся с 2020 года, запрос на поддержку Folia в его трекере висит "
                            + "с марта 2024. Существует активный форк VaultUnlocked.",
                    "Vault has not been updated since 2020; its Folia support request has been open "
                            + "since March 2024. VaultUnlocked is the maintained fork.",
                    "Если планируешь Folia — посмотри в сторону VaultUnlocked.",
                    "Consider VaultUnlocked if you are moving to Folia."))
                    .evidence(declared ? "объявлен в plugin.yml" : "используется в коде"));
        }
    }

    // ---- safety heuristics ------------------------------------------------
    // These never affect the compatibility verdict. They mean "a human should look",
    // not "this is malware".

    private void checkRuntimeExec(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("sec.process-execution", Severity.SECURITY, new Text(
                "Запускает внешние процессы",
                "Starts external processes",
                "Плагин вызывает Runtime.exec или ProcessBuilder. Для бэкап- и рестарт-плагинов это "
                        + "нормально, для всего остального — повод посмотреть, что именно запускается.",
                "The plugin calls Runtime.exec or ProcessBuilder. Normal for backup and restart "
                        + "plugins, worth a look anywhere else.",
                "Сверить с назначением плагина. Если это чат-плагин — вопросов больше, чем ответов.",
                "Compare against what the plugin claims to do."));
        for (String ref : f.memberRefs) {
            if (ref.equals("java/lang/Runtime#exec") || ref.startsWith("java/lang/ProcessBuilder#")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkDynamicClassLoading(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("sec.dynamic-class-loading", Severity.SECURITY, new Text(
                "Загружает код во время работы",
                "Loads code at runtime",
                "Найдены URLClassLoader или defineClass. Так работают легитимные загрузчики "
                        + "библиотек — и так же работает подгрузка полезной нагрузки извне.",
                "URLClassLoader or defineClass found. Legitimate library loaders do this - so does "
                        + "remote payload loading.",
                "Посмотреть, откуда берётся загружаемый код: из локального файла или из сети.",
                "Check whether the loaded code comes from a local file or from the network."));
        for (String cls : f.referencedClasses) {
            if (cls.equals("java/net/URLClassLoader")) {
                finding.evidence("URLClassLoader");
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.endsWith("#defineClass")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkSuspiciousUrls(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("sec.suspicious-endpoints", Severity.SECURITY, new Text(
                "Обращения к нетипичным адресам",
                "Unusual network endpoints",
                "В строках найдены адреса, по которым обычно не ходят плагины: сырые IP, "
                        + "пейстбины, вебхуки, туннели.",
                "String literals point at endpoints plugins rarely use: raw IPs, pastebins, "
                        + "webhooks, tunnels.",
                "Проверить вручную. Легитимные плагины ходят на свои домены и на известные API.",
                "Check manually. Legitimate plugins talk to their own domain or well-known APIs."));
        for (String s : f.stringConstants) {
            String lower = s.toLowerCase();
            if (RAW_IP_URL.matcher(s).matches()) {
                finding.evidence(trim(s, 70));
                continue;
            }
            for (String host : SUSPICIOUS_URL_HOSTS) {
                if (lower.contains(host)) {
                    finding.evidence(trim(s, 70));
                    break;
                }
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkServerSecrets(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("sec.server-files", Severity.SECURITY, new Text(
                "Читает служебные файлы сервера",
                "Reads server configuration files",
                "Плагин упоминает файлы, где лежат пароль RCON, список операторов и баны. Для "
                        + "админ-плагинов это нормально; в сочетании с сетевой активностью — нет.",
                "The plugin references files holding the RCON password, operator list and bans. "
                        + "Normal for admin tooling; not normal alongside network activity.",
                "Смотреть вместе с находками по сети: чтение + отправка наружу — плохой признак.",
                "Read together with the network findings: reading plus sending is a bad sign."));
        for (String s : f.stringConstants) {
            for (String marker : SERVER_SECRET_MARKERS) {
                if (s.contains(marker)) {
                    finding.evidence(marker);
                    break;
                }
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    /**
     * Flags a handful of classes squatting inside a well-known library package.
     *
     * <p>A jar that shades a library carries hundreds of its classes. A jar that carries one
     * or two classes under, say, {@code org/apache/commons/lang3/} is not shading anything -
     * something is wearing that package as a costume. This is exactly the shape of the
     * backdoor the Russian admin community dissected in May 2026.
     */
    private void checkMasqueradingClasses(JarFacts f, List<Finding> out) {
        Finding finding = new Finding("sec.masquerading-package", Severity.SECURITY, new Text(
                "Одиночные классы в чужом пакете",
                "Stray classes inside a well-known library package",
                "В пакете известной библиотеки лежит всего несколько классов. При нормальном "
                        + "шейдинге их сотни. Так маскируют посторонний код: именно в таком виде в "
                        + "мае 2026 нашли бэкдор, притворявшийся org.apache.commons.lang3.",
                "Only a couple of classes sit inside a well-known library package. Real shading "
                        + "brings hundreds. This is how foreign code hides - the backdoor found in "
                        + "May 2026 wore org.apache.commons.lang3 as a costume.",
                "Открыть эти классы декомпилятором до того, как ставить плагин на живой сервер.",
                "Decompile these classes before putting the plugin on a live server."));

        for (String prefix : KNOWN_LIBRARY_PREFIXES) {
            List<String> hits = new ArrayList<>();
            for (String own : f.ownClasses) {
                if (own.startsWith(prefix)) {
                    hits.add(own);
                }
            }
            if (!hits.isEmpty() && hits.size() <= 4) {
                for (String hit : hits) {
                    finding.evidence(hit.replace('/', '.'));
                }
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    // ------------------------------------------------------------- helpers

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        for (String s : list) {
            if (s.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String tail = slash < 0 ? internalName : internalName.substring(slash + 1);
        int dollar = tail.indexOf('$');
        return dollar < 0 ? tail : tail.substring(0, dollar);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    }

    private static String trim(String s, int max) {
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    /** "1.21" -> 1.21, "1.13" -> 1.13; 0 when unparseable. */
    private static double parseApiVersion(String raw) {
        try {
            String[] parts = raw.split("\\.");
            if (parts.length >= 2) {
                return Double.parseDouble(parts[0] + "." + parts[1]);
            }
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
