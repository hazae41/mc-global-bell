package fr.rhaz.minecraft

import com.google.gson.JsonParser
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.ChatColor.AQUA
import net.md_5.bungee.api.ChatColor.LIGHT_PURPLE
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.config.Configuration
import net.md_5.bungee.config.ConfigurationProvider
import net.md_5.bungee.config.YamlConfiguration
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.nio.file.Files
import java.util.*
import java.util.concurrent.TimeUnit

lateinit var NetworkMsgs: NetworkMsgsPlugin
class NetworkMsgsPlugin : Plugin() {

    init {
        NetworkMsgs = this
        File(".noads").delete()
    }

    lateinit var config: Configuration

    lateinit var cplayers: Configuration
    lateinit var silents: MutableList<String>

    var players = HashMap<UUID, String>()

    override fun onEnable() {
        donate(AQUA); update(10239, LIGHT_PURPLE)
        this.proxy.pluginManager.registerListener(this, listener)
        this.proxy.pluginManager.registerCommand(this, cmd)
        reload()
    }

    val configfile by lazy{File(dataFolder, "config.yml")}
    val playersfile by lazy{File(dataFolder, "players.yml")}

    fun reload() {
        config = load(configfile) ?: return
        cplayers = load(playersfile) ?: return
        silents = cplayers.getStringList("silent")
    }

    val listener = object: Listener{

        @EventHandler(priority = EventPriority.HIGH)
        fun onConnection(e: ServerSwitchEvent) =
            proxy.scheduler.schedule(this@NetworkMsgsPlugin, {onConnected(e)}, 2, TimeUnit.SECONDS)

        var lastjoin = 0L
        fun onConnected(e: ServerSwitchEvent){

            if(e.player !in proxy.players) return

            if(players[e.player.uniqueId] != null) return
            players[e.player.uniqueId] = e.player.server.info.name

            if(e.player.name in silents) return

            val now = System.currentTimeMillis()
            if((now - lastjoin) < 500) return
            lastjoin = now

            val toServer = e.player.server.info.name
            val toPlayers = e.player.server.info.players
            val allPlayers = HashSet<ProxiedPlayer>(proxy.players)
            allPlayers.removeAll(toPlayers)

            listOf("welcome", "join-to", "join-all").forEach{

                var msg = config.getString(it)

                if(it == "welcome") {
                    val players = cplayers.getStringList("players")
                    if (e.player.name in players) return@forEach
                    players.add(e.player.name)
                    cplayers.set("players", players)
                    cplayers.save(playersfile)
                }

                if(it == "join-to")
                    if(toServer in config.getSection("servers").keys)
                        msg = config.getSection("servers.$toServer").getString(it)

                if(msg.isEmpty()) return@forEach

                val players = when(it){
                    "join-to" -> toPlayers
                    "join-all" -> allPlayers
                    "welcome" -> proxy.players
                    else -> mutableListOf()
                }

                for (player in players) {

                    if(config.getBoolean("use-permission"))
                        if(!player.hasPermission("nmsg.receive")) continue

                    val out = msg
                            .replace("%player%", e.player.displayName)
                            .replace("%realname%", e.player.name)
                            .replace("%to-server%", alias(toServer))

                    for(line in out.split("%newline%"))
                        player.msg(line)
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        fun onDisconnection(e: PlayerDisconnectEvent) =
            proxy.scheduler.schedule(this@NetworkMsgsPlugin, {onDisconnected(e)}, 2, TimeUnit.SECONDS)

        var lastleave = 0L
        fun onDisconnected(e: PlayerDisconnectEvent){

            if(e.player in proxy.players) return

            val name = players[e.player.uniqueId] ?: return

            players.remove(e.player.uniqueId)

            if(e.player.name in silents) return

            val now = System.currentTimeMillis()
            if((now - lastleave) < 500) return
            lastleave = now

            val fromServer = proxy.getServerInfo(name).name
            val fromPlayers = proxy.getServerInfo(name).players
            val allPlayers = HashSet<ProxiedPlayer>(proxy.players)
            allPlayers.removeAll(fromPlayers)

            listOf("leave-all", "leave-from").forEach{
                var msg = config.getString(it)

                if(it == "leave-from")
                    if(fromServer in config.getSection("servers").keys)
                        msg = config.getSection("servers.$fromServer").getString(it)

                if(msg.isEmpty()) return@forEach

                val players = when(it) {
                    "leave-all" -> allPlayers
                    "leave-from" -> fromPlayers
                    else -> mutableListOf()
                }

                for (player in players) {

                    if(config.getBoolean("use-permission"))
                        if(!player.hasPermission("nmsg.receive")) continue

                    val out = msg
                            .replace("%player%", e.player.displayName)
                            .replace("%realname%", e.player.name)
                            .replace("%from-server%", alias(fromServer))

                    for(line in out.split("%newline%"))
                        player.msg(line)
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        fun onSwitch(e: ServerSwitchEvent) {

            if(e.player !in proxy.players) return

            val name = players[e.player.uniqueId] ?: return

            if(e.player.name in silents) return

            val allPlayers = HashSet<ProxiedPlayer>(proxy.players)
            val fromPlayers = proxy.getServerInfo(name).players
            val toPlayers = e.player.server.info.players
            allPlayers.removeAll(fromPlayers)
            allPlayers.removeAll(toPlayers)
            val fromServer = proxy.getServerInfo(name).name
            val toServer = e.player.server.info.name

            listOf("switch-all", "switch-from", "switch-to").forEach {
                var msg = config.getString(it)

                val server = when(it){
                    "switch-to" -> toServer
                    "switch-from" -> fromServer
                    else -> String()
                }

                if(server.any())
                    if (server in config.getSection("servers").keys)
                        msg = config.getSection("servers.$server").getString(it)

                if(msg.isEmpty()) return@forEach

                val players = when(it){
                    "switch-all" -> allPlayers
                    "switch-to" -> toPlayers
                    "switch-from" -> fromPlayers
                    else -> mutableListOf()
                }

                for(player in players){

                    if(config.getBoolean("use-permission"))
                        if(!player.hasPermission("nmsg.receive")) continue

                    val out = msg
                            .replace("%player%", e.player.displayName)
                            .replace("%realname%", e.player.name)
                            .replace("%from-server%", alias(fromServer))
                            .replace("%to-server%", alias(toServer))

                    for(line in out.split("%newline%")) player.msg(line)
                }
            }
        }
    }

    fun alias(name: String) = name.also{
        if(name !in config.getSection("servers").keys) return@also
        val section = config.getSection("servers.$name")
        if("alias" !in section.keys) return@also
        return section.getString("alias")
    }

    val cmd = object: Command("nmsg") {

        override fun execute(sender: CommandSender, args: Array<String>) {

            val help = {
                sender.msg("&6NetworkMsgs &7v${description.version}&8:")
                sender.msg("&7/nmsg &6silent")
                sender.msg("&7/nmsg &6reload")
                sender.msg("&7/nmsg &6donate")
            }

            val noperm = {sender.msg("§cYou do not have permission")}

            if(args.isEmpty()) return help()
            when(args[0].toLowerCase()){
                "silent" -> {
                    if(!sender.hasPermission("nmsg.silent")) return noperm()
                    val silent = when(sender.name in silents){
                        true -> false.also { silents.remove(sender.name) }
                        false -> true.also { silents.add(sender.name) }
                    }
                    sender.msg("§cSilent mode is now: $silent")
                    cplayers.set("silent", silents)
                    cplayers.save(playersfile)
                }
                "reload" -> {
                    if(!sender.hasPermission("nmsg.reload")) return noperm()
                    sender.msg("Config reloaded!").also { reload() }
                }
                "donate" -> """
                |  If you like my softwares or you just want to support me,
                |  I'd enjoy donations.
                |  By donating, you're going to encourage me to continue
                |  developing quality softwares.
                |  And you'll be added to the donators list!
                |  Click here to donate: http://dev.rhaz.fr/donate
                """.trimMargin().split("\n").map {
                    text(it).apply {
                        color = ChatColor.LIGHT_PURPLE
                        clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, "http://dev.rhaz.fr/donate")
                    }
                }.forEach { sender.sendMessage(it) }
                else -> help()
            }

        }
    }
}

fun CommandSender.msg(msg: String) = msg(text(msg))
fun CommandSender.msg(text: TextComponent) = sendMessage(text)
fun text(string: String) = TextComponent(string.replace("&", "§"))

infix fun String.newerThan(v: String): Boolean = false.also{
    val s1 = split('.')
    val s2 = v.split('.')
    for(i in 0..Math.max(s1.size,s2.size)){
        if(i !in s1.indices) return false
        if(i !in s2.indices) return true
        if(s1[i] > s2[i]) return true
        if(s1[i] < s2[i]) return false
    }
}

fun spiget(id: Int): String = try {
    val base = "https://api.spiget.org/v2/resources/"
    val conn = URL("$base$id/versions?size=100").openConnection()
    val json = InputStreamReader(conn.inputStream).let{ JsonParser().parse(it).asJsonArray}
    json.last().asJsonObject["name"].asString
} catch(e: IOException) {e.printStackTrace(); "0"}

fun Plugin.donate(color: ChatColor) {
    File(".noads").apply { if(exists()) return else createNewFile() }

    val plugins = proxy.pluginManager.plugins
            .filter {it.description.author in listOf("Hazae41", "RHazDev")}
            .map {it.description.name}

    proxy.console.sendMessage(text(
            """
    |
    |    __         _    ____  __   ___
    |   |__) |__|  /_\   ___/ |  \ |__  \  /
    |   |  \ |  | /   \ /___  |__/ |___  \/
    |
    |   It seems you use $plugins
    |
    |   If you like my softwares or you just want to support me, I'd enjoy donations.
    |   By donating, you're going to encourage me to continue developing quality softwares.
    |   And you'll be added to the donators list!
    |
    |   Click here to donate: http://dev.rhaz.fr/donate
    |
    """.trimMargin("|")).apply {
        this.color = color
        clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, "http://dev.rhaz.fr/donate")
    })
}

fun Plugin.update(id: Int, color: ChatColor) {

    if(!(spiget(id) newerThan description.version)) return

    val message = text("An update is available for ${description.name}!").apply {
        val url = "https://www.spigotmc.org/resources/$id"
        text += "\nDownload it here: $url"
        this.color = color
        clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, url)
    }

    proxy.scheduler.schedule(this, {
        proxy.console.sendMessage(message)
    }, 0, TimeUnit.SECONDS)

    proxy.pluginManager.registerListener(this, object : Listener {
        @EventHandler
        fun onJoin(e: PostLoginEvent) {
            if (e.player.hasPermission("rhaz.update"))
                e.player.sendMessage(message)
        }
    })
}

val provider = ConfigurationProvider.getProvider(YamlConfiguration::class.java)!!
fun Plugin.load(file: File) = try {
    if (!dataFolder.exists()) dataFolder.mkdir()
    if (!file.exists()) Files.copy(getResourceAsStream(file.name), file.toPath())
    provider.load(file)
} catch (e: IOException){ e.printStackTrace(); null }
fun Configuration.save(file: File) = provider.save(this, file)