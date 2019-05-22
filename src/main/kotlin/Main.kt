package hazae41.minecraft

import hazae41.minecraft.kotlin.bungee.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.kotlin.lowerCase
import hazae41.minecraft.kotlin.not
import net.md_5.bungee.api.ChatColor.AQUA
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import net.md_5.bungee.event.EventPriority.HIGHEST
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.collections.set

class GlobalBell: BungeePlugin() {

    val ServerInfo.conf get() = Config.Server(name)
    object Config: ConfigFile("config"){
        val silentPerm by string("silent-permission")
        val receivePerm by string("receive-permission")
        val preventBots by boolean("prevent-bots")

        val welcome by string("welcome")
        val joinTo by string("join-to")
        val joinAll by string("join-all")
        val leaveFrom by string("leave-from")
        val leaveAll by string("leave-all")
        val switchTo by string("switch-to")
        val switchFrom by string("switch-from")
        val switchAll by string("switch-all")

        val servers by section("servers")
        val Servers get() = servers?.keys?.map(::Server)
        class Server(val name: String): ConfigSection(this, name){
            constructor(server: ServerInfo): this(server.name)
            val _alias by string("alias")
            val alias get() = _alias.not("") ?: name
            val _welcome by string("welcome")
            val welcome get() = _welcome.not("") ?: Config.welcome
            val _joinTo by string("join-to")
            val joinTo get() = _joinTo.not("") ?: Config.joinTo
            val _leaveFrom by string("leave-from")
            val leaveFrom get() = _leaveFrom.not("") ?: Config.leaveFrom
            val _switchTo by string("switch-to")
            val switchTo get() = _switchTo.not("") ?: Config.switchTo
            val _switchFrom by string("switch-from")
            val switchFrom get() = _switchFrom.not("") ?: Config.switchFrom
        }

        object Players: ConfigFile("players"){
            var silents by stringList("silents")
            var players by stringList("players")
        }
    }

    var onlines = HashMap<UUID, String>()

    val alias = fun(name: String) = Config.Server(name).alias.not("") ?: name

    override fun onEnable() = catch(::error){
        update(10239, AQUA)
        init(Config, Config.Players)

        listen<ServerSwitchEvent>(HIGHEST){
            schedule(delay = 2, unit = SECONDS){switched(it); connected(it)}
        }
        listen<PlayerDisconnectEvent>(HIGHEST){
            schedule(delay = 2, unit = SECONDS){disconnected(it)}
        }
        command("gbell", command)
    }

    val command = fun BungeeSender.(args:Array<String>){
        val noperm = { msg("&cYou do not have permission") }
        fun help() = listOf(
            "&6GlobalBell &7v${description.version}&8:",
            "&7/gbell &6silent",
            "&7/gbell &6reload"
        ).forEach(::msg)

        if(args.isEmpty()) return help()
        when(args[0].lowerCase){
            "silent" -> {
                if(this !is ProxiedPlayer)
                return msg("&cYou're not a player")

                if(!hasPermission("gbell.silent"))
                return noperm()

                if(hasPermission(Config.silentPerm))
                return msg("&cYou are forced to be silent")

                if (name in Config.Players.silents) {
                    Config.Players.silents -= name
                    msg("&bSilent mode is now disabled")
                } else {
                    Config.Players.silents += name
                    msg("&bSilent mode is now enabled")
                }
            }
            "reload" -> {
                if(!hasPermission("gbell.reload"))
                return noperm()
                catch<Exception>(::msg){
                    Config.reload()
                    Config.Players.reload()
                    msg("Config reloaded!")
                }
            }
            else -> help()
        }
    }

    var lastjoin = 0L
    val connected = fun(e: ServerSwitchEvent){

        if(e.player !in proxy.players) return
        if(e.player.uniqueId in onlines) return
        val to = e.player.server.info
        onlines[e.player.uniqueId] = to.name

        if(e.player.name in Config.Players.silents) return
        if(e.player.hasPermission(Config.silentPerm)) return

        if(Config.preventBots){
            val now = System.currentTimeMillis()
            if((now - lastjoin) < 500) return
            lastjoin = now
        }

        val all = proxy.players.toMutableSet().also {
            it -= to.players
        }

        listOf("welcome", "join-to", "join-all").forEach h@{ type ->

            if(type == "welcome") {
                val uuid = e.player.uniqueId.toString()
                if(uuid in Config.Players.players) return@h
                if(e.player.name in Config.Players.players) return@h
                Config.Players.players += uuid
            }

            val msg = when(type){
                "join-to" -> to.conf.joinTo
                "welcome" -> Config.welcome
                "join-all" -> Config.joinAll
                else -> null!!
            }

            if(msg.isBlank()) return@h

            val players = when(type){
                "join-to" -> to.players
                "join-all" -> all
                "welcome" -> proxy.players
                else -> null!!
            }.toMutableList()

            if(Config.receivePerm.isNotEmpty())
                players.retainAll{ it.hasPermission(Config.receivePerm) }

            val lines = msg
                .replace("%player%", e.player.displayName)
                .replace("%realname%", e.player.name)
                .replace("%to-server%", to.conf.alias)
                .split("\n")

            players.forEach { p -> lines.forEach(p::msg) }
        }
    }

    var lastleave = 0L
    val disconnected = fun(e: PlayerDisconnectEvent){

        if(e.player in proxy.players) return
        val from = proxy.getServerInfo(onlines.remove(e.player.uniqueId) ?: return)

        if(e.player.name in Config.Players.silents) return
        if(e.player.hasPermission(Config.silentPerm)) return

        if(Config.preventBots){
            val now = System.currentTimeMillis()
            if((now - lastleave) < 500) return
            lastleave = now
        }

        val all = proxy.players.toHashSet().also { it -= from.players }

        listOf("leave-all", "leave-from").forEach h@{

            val msg = when(it){
                "leave-from" -> from.conf.leaveFrom
                "leave-all" -> Config.leaveAll
                else -> null!!
            }

            if(msg.isBlank()) return@h

            val players = when(it) {
                "leave-from" -> from.players
                "leave-all" -> all
                else -> null!!
            }.toMutableList()

            if(Config.receivePerm.isNotEmpty())
                players.retainAll{ it.hasPermission(Config.receivePerm) }

            val lines = msg
                .replace("%player%", e.player.displayName)
                .replace("%realname%", e.player.name)
                .replace("%from-server%", from.conf.alias)
                .split("\n")

            players.forEach{ p -> lines.forEach(p::msg) }

        }
    }

    val switched = fun(e: ServerSwitchEvent) {

        if(e.player !in proxy.players) return

        val from = proxy.getServerInfo(onlines[e.player.uniqueId] ?: return)
        val to = e.player.server.info
        onlines[e.player.uniqueId] = to.name

        if(e.player.name in Config.Players.silents) return
        if(e.player.hasPermission(Config.silentPerm)) return

        val all = HashSet<ProxiedPlayer>(proxy.players).also {
            it -= from.players
            it -= to.players
        }

        listOf("switch-all", "switch-from", "switch-to").forEach h@{

            val msg = when(it){
                "switch-to" -> to.conf.switchTo
                "switch-from" -> from.conf.switchFrom
                "switch-all" -> Config.switchAll
                else -> null!!
            }

            if(msg.isBlank()) return@h

            val players = when(it){
                "switch-to" -> to.players
                "switch-from" -> from.players
                "switch-all" -> all
                else -> null!!
            }.toMutableList()

            if(Config.receivePerm.isNotEmpty())
                players.retainAll{ it.hasPermission(Config.receivePerm) }

            val lines = msg
                .replace("%player%", e.player.displayName)
                .replace("%realname%", e.player.name)
                .replace("%from-server%", alias(from.name))
                .replace("%to-server%", alias(to.name))
                .split("\n")

            players.forEach{p -> lines.forEach(p::msg)}
        }
    }
}