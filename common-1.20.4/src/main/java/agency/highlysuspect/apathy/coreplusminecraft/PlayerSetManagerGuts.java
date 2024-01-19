package agency.highlysuspect.apathy.coreplusminecraft;

import agency.highlysuspect.apathy.core.Apathy;
import agency.highlysuspect.apathy.core.CoreMobOptions;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PlayerSetManagerGuts {
	public PlayerSetManagerGuts(Runnable setDirty) {
		this.setDirty = setDirty;
	}
	
	private final Runnable setDirty;
	public final ConcurrentHashMap<String, Entry> playerSets = new ConcurrentHashMap<>(); //Idk if it needs to be a concurrent map really but.... okay.
	
	public void load(CompoundTag tag) {
		playerSets.clear();
		CompoundTag allSets = tag.getCompound("PlayerSets");
		for(String name : allSets.getAllKeys()) {
			playerSets.put(name, Entry.fromTag(allSets.getCompound(name)));
		}
	}
	
	public CompoundTag save(CompoundTag tag) {
		CompoundTag allSets = new CompoundTag();
		for(Map.Entry<String, Entry> entry : playerSets.entrySet()) {
			allSets.put(entry.getKey(), entry.getValue().toTag());
		}
		tag.put("PlayerSets", allSets);
		
		return tag;
	}
	
	///
	
	public boolean playerInSet(ServerPlayer player, String name) {
		Entry set = playerSets.get(name);
		if(set == null) return false;
		else return set.contains(player);
	}
	
	public void syncWithConfig() {
		Optional<String> configSetName = Apathy.instance.mobCfg.get(CoreMobOptions.playerSetName);
		if(configSetName.isPresent()) {
			String name = configSetName.get();
			boolean selfselectiness = Apathy.instance.mobCfg.get(CoreMobOptions.playerSetSelfSelect);
			
			Entry set = playerSets.get(name);
			
			//Create the playerset, if it does not exist
			if(set == null) {
				set = Entry.newEmpty(selfselectiness);
				playerSets.put(name, set);
				setDirty.run();
			}
			
			//Set its selfselectness, if it's different
			if(set.selfSelect != selfselectiness) {
				playerSets.put(name, set.withSelfSelect(selfselectiness));
				setDirty.run();
			}
		}
	}
	
	///
	
	public JoinResult join(ServerPlayer player, String name, boolean op) {
		Entry set = playerSets.get(name);
		
		if(set == null) return JoinResult.NO_SUCH_SET;
		if(!op && !set.selfSelect()) return JoinResult.NOT_SELF_SELECT;
		if(set.contains(player)) return JoinResult.ALREADY_IN_SET;
		
		playerSets.put(name, set.withAddition(player));
		setDirty.run();
		
		return JoinResult.SUCCESS;
	}
	
	public enum JoinResult {
		SUCCESS,
		NO_SUCH_SET,
		ALREADY_IN_SET,
		NOT_SELF_SELECT,
	}
	
	///
	
	public PartResult part(ServerPlayer player, String name, boolean op) {
		Entry set = playerSets.get(name);
		
		if(set == null) return PartResult.NO_SUCH_SET;
		if(!op && !set.selfSelect()) return PartResult.NOT_SELF_SELECT;
		if(!set.contains(player)) return PartResult.ALREADY_NOT_IN_SET;
		
		playerSets.put(name, set.withRemoval(player));
		setDirty.run();
		
		return PartResult.SUCCESS;
	}
	
	public enum PartResult {
		SUCCESS,
		NO_SUCH_SET,
		ALREADY_NOT_IN_SET,
		NOT_SELF_SELECT,
	}
	
	///
	
	public DeleteResult delete(String name) {
		Entry removed = playerSets.remove(name);
		if(removed == null) return DeleteResult.NO_SUCH_SET;
		
		setDirty.run();
		return DeleteResult.SUCCESS;
	}
	
	public enum DeleteResult {
		SUCCESS,
		NO_SUCH_SET,
	}
	
	///
	
	public EditResult edit(String name, boolean newSelfSelect) {
		Entry set = playerSets.get(name);
		
		if(set == null) return EditResult.NO_SUCH_SET;
		if(set.selfSelect && newSelfSelect) return EditResult.ALREADY_SELF_SELECT;
		if(!set.selfSelect && !newSelfSelect) return EditResult.ALREADY_NOT_SELF_SELECT;
		
		playerSets.put(name, set.withSelfSelect(newSelfSelect));
		setDirty.run();
		
		return EditResult.SUCCESS;
	}
	
	public enum EditResult {
		SUCCESS,
		NO_SUCH_SET,
		ALREADY_SELF_SELECT,
		ALREADY_NOT_SELF_SELECT,
	}
	
	///
	
	public CreateResult create(String name, boolean newSelfSelect) {
		if(playerSets.get(name) != null) return CreateResult.ALREADY_EXISTS;
		
		playerSets.put(name, Entry.newEmpty(newSelfSelect));
		setDirty.run();
		
		return CreateResult.SUCCESS;
	}
	
	public enum CreateResult {
		SUCCESS,
		ALREADY_EXISTS,
	}
	
	///
	
	public boolean isEmpty() {
		return playerSets.isEmpty();
	}
	
	public Set<Map.Entry<String, Entry>> entrySet() {
		return playerSets.entrySet();
	}
	
	/// command printouts
	
	public static CompletableFuture<Suggestions> suggestSelfSelectPlayerSets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		PlayerSetManagerGuts guts = ApathyPlusMinecraft.instanceMinecraft.getFor(context);
		return SharedSuggestionProvider.suggest(guts.playerSets.entrySet().stream()
				.filter(entry -> entry.getValue().selfSelect())
				.map(Map.Entry::getKey)
				.collect(Collectors.toList()),
			builder);
	}
	
	public static CompletableFuture<Suggestions> suggestAllPlayerSets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		PlayerSetManagerGuts guts = ApathyPlusMinecraft.instanceMinecraft.getFor(context);
		return SharedSuggestionProvider.suggest(new ArrayList<>(guts.playerSets.keySet()), builder);
	}
	
	public Component printAllPlayerSets() {
		//prints all sets such that self select ones are marked with a "(self-select)" note
		return ApathyPlusMinecraft.instanceMinecraft.formatList(
			playerSets.entrySet(),
			entry -> ApathyPlusMinecraft.instanceMinecraft.literal(entry.getKey() + (entry.getValue().selfSelect() ? " (self-select)" : ""))
		);
	}
	
	public static final class Entry {
		public Entry(Set<UUID> members, boolean selfSelect) {
			this.members = members;
			this.selfSelect = selfSelect;
		}
		
		private final Set<UUID> members;
		private final boolean selfSelect;
		
		public static Entry newEmpty(boolean selfSelect) {
			return new Entry(new HashSet<>(), selfSelect);
		}
		
		public boolean contains(ServerPlayer player) {
			return members.contains(player.getUUID());
		}
		
		public Entry withAddition(ServerPlayer newPlayer) {
			Set<UUID> members2 = new HashSet<>(members);
			members2.add(newPlayer.getUUID());
			return new Entry(members2, this.selfSelect);
		}
		
		public Entry withRemoval(ServerPlayer removePlayer) {
			Set<UUID> members2 = new HashSet<>(members);
			members2.remove(removePlayer.getUUID());
			return new Entry(members2, this.selfSelect);
		}
		
		public Entry withSelfSelect(boolean newSelfSelect) {
			return new Entry(members, newSelfSelect);
		}
		
		public static Entry fromTag(CompoundTag tag) {
			HashSet<UUID> members = new HashSet<>();
			ListTag memberList = tag.getList("Members", 11);
			for(Tag value : memberList) {
				members.add(NbtUtils.loadUUID(value));
			}
			
			return new Entry(members, tag.getBoolean("SelfSelect"));
		}
		
		public CompoundTag toTag() {
			CompoundTag tag = new CompoundTag();
			
			ListTag memberList = new ListTag();
			for(UUID uuid : members) {
				memberList.add(NbtUtils.createUUID(uuid));
			}
			tag.put("Members", memberList);
			tag.putBoolean("SelfSelect", selfSelect);
			return tag;
		}
		
		public Set<UUID> members() {
			return members;
		}
		
		public boolean selfSelect() {
			return selfSelect;
		}
		
		@Override
		public boolean equals(Object obj) {
			if(obj == this) return true;
			else if(obj == null || obj.getClass() != this.getClass()) return false;
			else return Objects.equals(this.members, ((Entry) obj).members) &&
					this.selfSelect == ((Entry) obj).selfSelect;
		}
		
		@Override
		public int hashCode() {
			return members.hashCode() ^ (selfSelect ? Integer.MAX_VALUE : 0);
		}
	}
}
