package fr.rhaz.minecraft

import fr.rhaz.minecraft.kotlin.*
import net.md_5.bungee.api.ChatColor.LIGHT_PURPLE
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.config.Configuration
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import net.md_5.bungee.event.EventPriority.HIGH
import java.lang.Exception
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
            ?: throw ex("Could not load config.yml")
    }

    val alias = fun(name: String) = name.also{
        val section = config.getSection("servers.$name") ?: return@also
        if("alias" !in section.keys) return@also
        return section.getString("alias")
    }

    override fun onEnable() = catch<Exception>(::severe){

        update(10239, LIGHT_PURPLE)

        load()

        listen<ServerSwitchEvent>(HIGH){
            switched(it)
            schedule(delay = 2, unit = SECONDS){connected(it)}
        }

        listen<PlayerDisconnectEvent>(HIGH){
            schedule(delay = 2, unit = SECONDS){disconnected(it)}
        }

        command("nmsg", command)
    }


    val command = fun BungeeSender.(args:Array<String>){
        if(this !is ProxiedPlayer) return msg("&cYou're not a player")
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
                if(!hasPermission("nmsg.silent"))
                    return noperm()
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
            "reload" ->
                if(hasPermission("nmsg.reload")){
                    catch<Exception>({msg(it); it.printStackTrace()}){
                        load(); msg("Config reloaded!")
                    }
                } else noperm()
            "donate" -> text("""
                    |If you like my softwares or you just want to support me,
                    |I'd enjoy donations.
                    |By donating, you're going to encourage me to continue
                    |developing quality softwares.
                    |And you'll be added to the donators list!
                    |Click here to donate: http://dev.rhaz.fr/donate
                """.trimMargin("|")).apply {
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

        if(players[e.player.uniqueId] != null) return
        players[e.player.uniqueId] = e.player.server.info.name

        val silents = cplayers.getStringList("silent")
        if(e.player.name in silents) return
        val perm = config.getString("silent-permission")
        if(e.player.hasPermission(perm)) return

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
                save(cplayers, dataFolder["players.yml"])
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

            info("sending to $players")

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

    var lastleave = 0L
    val disconnected = fun(e: PlayerDisconnectEvent){

        if(e.player in proxy.players) return

        val name = players[e.player.uniqueId] ?: return

        players.remove(e.player.uniqueId)

        val silents = cplayers.getStringList("silent")
        if(e.player.name in silents) return
        val perm = config.getString("silent-permission")
        if(e.player.hasPermission(perm)) return

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

    val switched = fun(e: ServerSwitchEvent) {

        if(e.player !in proxy.players) return

        val name = players[e.player.uniqueId] ?: return

        val silents = cplayers.getStringList("silent")
        if(e.player.name in silents) return
        val perm = config.getString("silent-permission")
        if(e.player.hasPermission(perm)) return

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