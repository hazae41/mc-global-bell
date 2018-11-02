package fr.rhaz.minecraft

import fr.rhaz.minecraft.kotlin.*
import net.md_5.bungee.api.ChatColor.AQUA
import net.md_5.bungee.api.ChatColor.LIGHT_PURPLE
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import net.md_5.bungee.config.Configuration
import net.md_5.bungee.event.EventPriority.HIGHEST
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS

class NetworkMsgs: BungeePlugin() {

    companion object { var instance: NetworkMsgs? = null }

    lateinit var config: Configuration
    lateinit var cplayers: Configuration

    var players = HashMap<UUID, String>()

    val load = fun(){
        config = load(dataFolder["config.yml"], "config.yml")
            ?: throw ex("Could not load config.yml")
        cplayers = load(dataFolder["players.yml"], "players.yml")
            ?: throw ex("Could not load players.yml")
    }

    val alias = fun(name: String) = name.also{
        val section = config.getSection("servers.$name") ?: return@also
        if("alias" !in section.keys) return@also
        return section.getString("alias")
    }

    override fun onEnable() = catch<Exception>(::severe){
        update(10239, AQUA)
        load()

        listen<ServerSwitchEvent>(HIGHEST){
            schedule(delay = 2, unit = SECONDS){switched(it); connected(it)}
        }
        listen<PlayerDisconnectEvent>(HIGHEST){
            schedule(delay = 2, unit = SECONDS){disconnected(it)}
        }
        command("nmsg", command)
    }


    val command = fun BungeeSender.(args:Array<String>){
        fun noperm() = msg("&cYou do not have permission")
        fun help() = listOf(
            "&6NetworkMsgs &7v${description.version}&8:",
            "&7/nmsg &6silent",
            "&7/nmsg &6reload",
            "&7/nmsg &6donate"
        ).forEach(::msg)

        if(args.isEmpty()) return help()
        when(args[0].lc){
            "silent" -> {
                if(this !is ProxiedPlayer)
                return msg("&cYou're not a player")

                if(!hasPermission("nmsg.silent"))
                return noperm()

                if(hasPermission(config.getString("silent-permission")))
                return msg("&cYou are forced to be silent")

                val silents = cplayers.getStringList("silent")
                if (name in silents) {
                    silents.remove(name)
                    msg("&bSilent mode is now disabled")
                } else {
                    silents.add(name)
                    msg("&bSilent mode is now enabled")
                }
                cplayers["silent"] = silents
                save(cplayers, dataFolder["players.yml"])
            }
            "reload" -> {
                if(!hasPermission("nmsg.reload"))
                return noperm()
                catch<Exception>(::msg){
                    load(); msg("Config reloaded!")
                }
            }
            "donate" -> text("""
                |If you like my softwares or you just want to support me,
                |I'd enjoy donations.
                |By donating, you're going to encourage me to continue
                |developing quality softwares.
                |And you'll be added to the donators list!
                |Click here to donate: http://dev.rhaz.fr/donate
                """.trimMargin("|")
            ).apply {
                color = LIGHT_PURPLE
                clickEvent = ClickEvent(OPEN_URL, "https://dev.rhaz.fr/donate")
                msg(this)
            }
            else -> help()
        }
    }

    var lastjoin = 0L
    val connected = fun(e: ServerSwitchEvent){

        if(e.player !in proxy.players) return
        if(e.player.uniqueId in players) return
        val to = e.player.server.info
        players[e.player.uniqueId] = to.name

        val silents = cplayers.getStringList("silent")
        if(e.player.name in silents) return
        val perm = config.getString("silent-permission")
        if(e.player.hasPermission(perm)) return

        if(config.getBoolean("prevent-bots")){
            val now = System.currentTimeMillis()
            if((now - lastjoin) < 500) return
            lastjoin = now
        }

        val all = proxy.players.toMutableSet()
        all.removeAll(to.players)

        listOf("welcome", "join-to", "join-all").forEach h@{

            if(it == "welcome") {
                val players = cplayers.getStringList("players")
                if (e.player.name in players) return@h
                players.add(e.player.name)
                cplayers.set("players", players)
                save(cplayers, dataFolder["players.yml"])
            }

            fun get(server: String) =
                config.getSection("servers.$server")
                ?.getString(it).not("")
                ?: config.getString(it)

            val msg = when(it){
                "join-to" -> get(to.name)
                else -> config.getString(it)
            }.not("") ?: return@h

            val players = when(it){
                "join-to" -> to.players
                "join-all" -> all
                "welcome" -> proxy.players
                else -> mutableListOf()
            }.toMutableList()

            val receive = config.getString("receive-permission")
            if(receive.isNotEmpty()) players.retainAll{ it.hasPermission(receive) }

            val lines = msg
                .replace("%player%", e.player.displayName)
                .replace("%realname%", e.player.name)
                .replace("%to-server%", alias(to.name))
                .split("\n")
            players.forEach { p -> lines.forEach(p::msg) }
        }
    }

    var lastleave = 0L
    val disconnected = fun(e: PlayerDisconnectEvent){

        if(e.player in proxy.players) return

        val from = proxy.getServerInfo(players.remove(e.player.uniqueId) ?: return)

        val silents = cplayers.getStringList("silent")
        if(e.player.name in silents) return
        val perm = config.getString("silent-permission")
        if(e.player.hasPermission(perm)) return

        if(config.getBoolean("prevent-bots")){
            val now = System.currentTimeMillis()
            if((now - lastleave) < 500) return
            lastleave = now
        }

        val all = proxy.players.toHashSet()
        all.removeAll(from.players)

        listOf("leave-all", "leave-from").forEach h@{

            fun get(server: String) =
                config.getSection("servers.$server")
                ?.getString(it).not("")
                ?: config.getString(it)

            val msg = when(it){
                "leave-from" -> get(from.name)
                else -> config.getString(it)
            }.not("") ?: return@h

            val players = when(it) {
                "leave-from" -> from.players
                else -> all
            }.toMutableList()

            val receive = config.getString("receive-permission")
            if(receive.isNotEmpty()) players.retainAll{ it.hasPermission(receive) }

            val lines = msg
                .replace("%player%", e.player.displayName)
                .replace("%realname%", e.player.name)
                .replace("%from-server%", alias(from.name))
                .split("\n")
            players.forEach{ p -> lines.forEach(p::msg) }

        }
    }

    val switched = fun(e: ServerSwitchEvent) {

        if(e.player !in proxy.players) return

        val from = proxy.getServerInfo(players[e.player.uniqueId] ?: return)
        val to = e.player.server.info
        players[e.player.uniqueId] = to.name

        val silents = cplayers.getStringList("silent")
        if(e.player.name in silents) return
        val perm = config.getString("silent-permission")
        if(e.player.hasPermission(perm)) return

        val all = HashSet<ProxiedPlayer>(proxy.players)
            .apply { removeAll(from.players); removeAll(to.players) }

        listOf("switch-all", "switch-from", "switch-to").forEach h@{

            fun get(server: String) =
                config.getSection("servers.$server")
                ?.getString(it).not("")
                ?: config.getString(it)

            val msg = when(it){
                "switch-to" -> get(to.name)
                "switch-from" -> get(from.name)
                else -> config.getString(it)
            }.not("") ?: return@h


            var players = when(it){
                "switch-to" -> to.players
                "switch-from" -> from.players
                else -> all
            }.toMutableList()

            val receive = config.getString("receive-permission")
            if(receive.isNotEmpty()) players.retainAll{ it.hasPermission(receive) }

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