package agency.highlysuspect.apathy.coreplusminecraft;

import agency.highlysuspect.apathy.core.Apathy;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.arguments.EntityArgument.getPlayers;
import static net.minecraft.commands.arguments.EntityArgument.players;

@SuppressWarnings("SameReturnValue")
public class ApathyCommands {
	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		//Do not trust the indentation lmao
		//I've been burned before
		//Be careful
		
		dispatcher.register(literal(Apathy.MODID)
			.then(literal("set")
				.then(literal("join")
					.then(argument("set", string()).suggests(PlayerSetManagerGuts::suggestSelfSelectPlayerSets)
						.executes(cmd -> joinSet(cmd, Collections.singletonList(cmd.getSource().getPlayerOrException()), getString(cmd, "set"), false))))
				.then(literal("part")
					.then(argument("set", string()).suggests(PlayerSetManagerGuts::suggestSelfSelectPlayerSets)
						.executes(cmd -> partSet(cmd, Collections.singletonList(cmd.getSource().getPlayerOrException()), getString(cmd, "set"), false))))
				.then(literal("show")
					.executes(cmd -> personalShow(cmd, cmd.getSource().getPlayerOrException()))))
			.then(literal("set-admin")
				.requires(src -> src.hasPermission(2))
				.then(literal("join")
					.then(argument("who", players())
						.then(argument("set", string()).suggests(PlayerSetManagerGuts::suggestAllPlayerSets)
							.executes(cmd -> joinSet(cmd, getPlayers(cmd, "who"), getString(cmd, "set"), true)))))
				.then(literal("part")
					.then(argument("who", players())
						.then(argument("set", string()).suggests(PlayerSetManagerGuts::suggestAllPlayerSets)
							.executes(cmd -> partSet(cmd, getPlayers(cmd, "who"), getString(cmd, "set"), true)))))
				.then(literal("show")
					.executes(ApathyCommands::adminShowSets))
				.then(literal("delete")
					.then(argument("set", string()).suggests(PlayerSetManagerGuts::suggestAllPlayerSets)
						.executes(cmd -> adminDeleteSet(cmd, getString(cmd, "set")))))
				.then(literal("create")
					.then(argument("name", word())
						.then(argument("self-select", bool())
							.executes(cmd -> adminCreateSet(cmd, getString(cmd, "name"), getBool(cmd, "self-select"))))))
				.then(literal("edit")
					.then(argument("set", string()).suggests(PlayerSetManagerGuts::suggestAllPlayerSets)
						.then(argument("self-select", bool())
							.executes(cmd -> adminEditSet(cmd, getString(cmd, "set"), getBool(cmd, "self-select")))))))
			.then(literal("reload")
				.requires(src -> src.hasPermission(2))
				.executes(ApathyCommands::reloadNow)));
	}
	
	//(scaffolding)
	private static void err(CommandContext<CommandSourceStack> cmd, String msg, Object... args) {
		cmd.getSource().sendFailure(lit(msg, args));
	}
	
	private static void personalMsg(CommandContext<CommandSourceStack> cmd, String msg, Object... args) {
		ApathyPlusMinecraft.instanceMinecraft.sendSuccess(cmd, () -> lit(msg, args), false);
	}
	
	private static void msg(CommandContext<CommandSourceStack> cmd, String msg, Object... args) {
		ApathyPlusMinecraft.instanceMinecraft.sendSuccess(cmd, () -> lit(msg, args), true);
	}
	
	private static Component lit(String msg, Object... args) {
		for(int i = 0; i < args.length; i++) if(args[i] instanceof Component) args[i] = ApathyPlusMinecraft.instanceMinecraft.stringifyComponent((Component) args[i]);
		return ApathyPlusMinecraft.instanceMinecraft.literal(String.format(msg, args));
	}
	
	//Joining, parting
	private static int joinSet(CommandContext<CommandSourceStack> cmd, Collection<ServerPlayer> players, String name, boolean op) {
		int successCount = 0;
		
		for(ServerPlayer player : players) {
			PlayerSetManagerGuts.JoinResult result = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd).join(player, name, op);
			switch(result) {
				case SUCCESS: {
					successCount++;
					msg(cmd, "%s joined set %s.", player.getName(), name);
					break;
				}
				case NO_SUCH_SET: err(cmd, "There isn't a set named %s. Try /apathy set-admin create.", name); break;
				case ALREADY_IN_SET: err(cmd, "Player %s is already in set %s. Try /apathy set part.", player.getName(), name); break;
				case NOT_SELF_SELECT: err(cmd, "Set %s is not a self-select set. Try /apathy set-admin join.", name); break;
			}
		}
		
		return successCount;
	}
	
	private static int partSet(CommandContext<CommandSourceStack> cmd, Collection<ServerPlayer> players, String name, boolean op) {
		int successCount = 0;
		
		for(ServerPlayer player : players) {
			PlayerSetManagerGuts.PartResult result = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd).part(player, name, op);
			switch(result) {
				case SUCCESS: {
					successCount++;
					msg(cmd, "%s parted set %s.", ApathyPlusMinecraft.instanceMinecraft.stringifyComponent(player.getName()), name);
					break;
				}
				case NO_SUCH_SET: err(cmd, "There isn't a set named %s. Try /apathy set-admin create.", name); break;
				case ALREADY_NOT_IN_SET: err(cmd, "Player %s is already not in set %s. Try /apathy set join.", player.getName(), name); break;
				case NOT_SELF_SELECT: err(cmd, "Set %s is not a self-select set. Try /apathy set-admin part.", name); break;
			}
		}
		
		return successCount;
	}
	
	//Showing
	private static int personalShow(CommandContext<CommandSourceStack> cmd, ServerPlayer player) {
		PlayerSetManagerGuts setManager = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd);
		
		if(setManager.isEmpty()) {
			err(cmd, "There aren't any player sets.");
		} else {
			personalMsg(cmd, "The following sets exist: %s", setManager.printAllPlayerSets());
		}
		
		for(Map.Entry<String, PlayerSetManagerGuts.Entry> entry : setManager.entrySet()) {
			if(setManager.playerInSet(player, entry.getKey())) {
				personalMsg(cmd, "You are in set %s.", entry.getKey());
			} else {
				err(cmd, "You are not in set %s.", entry.getKey());
			}
		}
		
		return 0;
	}
	
	private static int adminShowSets(CommandContext<CommandSourceStack> cmd) {
		PlayerSetManagerGuts setManager = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd);
		
		if(setManager.isEmpty()) {
			err(cmd, "There aren't any player sets.");
		} else {
			personalMsg(cmd, "The following player sets exist: %s", setManager.printAllPlayerSets());
			PlayerList mgr = cmd.getSource().getServer().getPlayerList();
			
			for(Map.Entry<String, PlayerSetManagerGuts.Entry> entry : setManager.entrySet()) {
				String name = entry.getKey();
				PlayerSetManagerGuts.Entry set = entry.getValue();
				
				personalMsg(cmd, "Set %s contains %s members.", name, set.members().size());
				for(UUID uuid : set.members()) {
					ServerPlayer player = mgr.getPlayer(uuid);
					personalMsg(cmd, player == null ?
						String.format(" - someone with UUID %s", uuid) :
						String.format(" - %s (UUID %s)", ApathyPlusMinecraft.instanceMinecraft.stringifyComponent(player.getName()), uuid));
				}
			}
		}
		
		return 0;
	}
	
	private static int adminEditSet(CommandContext<CommandSourceStack> cmd, String name, boolean selfSelect) {
		PlayerSetManagerGuts setManager = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd);
		PlayerSetManagerGuts.EditResult result = setManager.edit(name, selfSelect);
		
		switch(result) {
			case SUCCESS: msg(cmd, selfSelect ? "Made set %s a self-select set." : "Made set %s a non-self-select set.", name); break;
			case NO_SUCH_SET: err(cmd, "There isn't a set named %s. Try /apathy set-admin create.", name); break;
			case ALREADY_SELF_SELECT: err(cmd, "Set %s is already self-select.", name); break;
			case ALREADY_NOT_SELF_SELECT: err(cmd, "Set %s is already not self-select.", name); break;
		}
		
		return result == PlayerSetManagerGuts.EditResult.SUCCESS ? 1 : 0;
	}
	
	private static int adminCreateSet(CommandContext<CommandSourceStack> cmd, String name, boolean selfSelect) {
		PlayerSetManagerGuts setManager = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd);
		PlayerSetManagerGuts.CreateResult result = setManager.create(name, selfSelect);
		
		switch(result) {
			case SUCCESS: msg(cmd, selfSelect ? "Created self-select set %s." : "Created non-self-select set %s.", name); break;
			case ALREADY_EXISTS: err(cmd, "There's already a set named %s. Try /apathy set-admin edit.", name); break;
		}
		
		return result == PlayerSetManagerGuts.CreateResult.SUCCESS ? 1 : 0;
	}
	
	private static int adminDeleteSet(CommandContext<CommandSourceStack> cmd, String name) {
		PlayerSetManagerGuts setManager = ApathyPlusMinecraft.instanceMinecraft.getFor(cmd);
		PlayerSetManagerGuts.DeleteResult result = setManager.delete(name);
		
		switch(result) {
			case SUCCESS: msg(cmd, "Player set %s deleted.", name); break;
			case NO_SUCH_SET: err(cmd, "There isn't a set named %s.", name); break;
		}
		
		return result == PlayerSetManagerGuts.DeleteResult.SUCCESS ? 1 : 0;
	}
	
	private static int reloadNow(CommandContext<CommandSourceStack> cmd) {
		boolean ok = Apathy.instance.refreshConfig();
		
		if(ok) msg(cmd, "Reloaded Apathy config files.");
		else err(cmd, "Error reloading Apathy config files. Check the server log.");
		
		return 0;
	}
}
