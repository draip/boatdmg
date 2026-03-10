package net.bara.boatdmg;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.argument.EntityArgumentType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;

import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Boatdmg implements ModInitializer {

	private final Map<UUID, Float> trackedBoats = new HashMap<>();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> registerBoat(dispatcher)
		);

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Iterator<UUID> iterator = trackedBoats.keySet().iterator();

			while (iterator.hasNext()) {
				UUID uuid = iterator.next();
				AbstractBoatEntity boat = null;

				for (var world : server.getWorlds()) {
					Entity entity = world.getEntity(uuid);
					if (entity instanceof AbstractBoatEntity b) {
						boat = b;
						break;
					}
				}

				if (boat != null) {
					float currentWobble = boat.getDamageWobbleStrength();
					float lastWobble = trackedBoats.get(uuid);

					if (currentWobble != lastWobble) {
						trackedBoats.put(uuid, currentWobble);
						String shortId = uuid.toString().substring(0, 4);

						for (PlayerEntity player : server.getPlayerManager().getPlayerList()) {
							MutableText message = makePrefix()
									.append(Text.literal(shortId)
											.setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.GRAY))))
									.append(Text.literal(": ")
											.setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.DARK_GRAY))))
									.append(Text.literal(
													String.format("%.4f", currentWobble)
															+ (currentWobble > lastWobble ? " ↑" : " ↓"))
											.setStyle(Style.EMPTY.withColor(
													TextColor.fromFormatting(
															currentWobble > lastWobble
																	? Formatting.RED
																	: Formatting.GREEN
													))));

							player.sendMessage(message, false);
						}
					}
				} else {
					iterator.remove();
					String shortId = uuid.toString().substring(0, 4);

					for (PlayerEntity player : server.getPlayerManager().getPlayerList()) {
						MutableText message = makePrefix()
								.append(Text.literal(shortId)
										.setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.GRAY))))
								.append(Text.literal(": ")
										.setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.DARK_GRAY))))
								.append(Text.literal("died ☠")
										.setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.DARK_RED))));

						player.sendMessage(message, false);
					}
				}
			}
		});
	}

	private void registerBoat(CommandDispatcher<ServerCommandSource> dispatcher) {
		LiteralArgumentBuilder<ServerCommandSource> boatCommand =
				CommandManager.literal("boat");

		boatCommand.then(
				CommandManager.argument("selector", EntityArgumentType.entities())
						.suggests(Boatdmg::onlyLookedAtUuidSuggestion)
						.executes(ctx -> {
							ServerCommandSource source = ctx.getSource();
							Collection<? extends Entity> entities =
									EntityArgumentType.getEntities(ctx, "selector");

							boolean foundBoat = false;

							for (Entity e : entities) {
								if (e instanceof AbstractBoatEntity boat) {
									trackedBoats.put(
											boat.getUuid(),
											boat.getDamageWobbleStrength()
									);
									foundBoat = true;

									source.sendFeedback(() ->
													makePrefix()
															.append(Text.literal("tracking")
																	.setStyle(Style.EMPTY.withColor(
																			TextColor.fromFormatting(Formatting.GRAY))))
															.append(Text.literal(": ")
																	.setStyle(Style.EMPTY.withColor(
																			TextColor.fromFormatting(Formatting.DARK_GRAY))))
															.append(Text.literal(boat.getUuid().toString())
																	.setStyle(Style.EMPTY.withColor(
																			TextColor.fromFormatting(Formatting.YELLOW)))),
											true
									);
								}
							}

							if (!foundBoat) {
								source.sendFeedback(() ->
												makePrefix()
														.append(Text.literal("that's not a boat!")
																.setStyle(Style.EMPTY.withColor(
																		TextColor.fromFormatting(Formatting.GRAY)))),
										false
								);
							}

							return 1;
						})
		);

		boatCommand.then(
				CommandManager.literal("grab")
						.executes(ctx -> {
							ServerCommandSource source = ctx.getSource();
							PlayerEntity player = source.getPlayer();
							if (player == null) return 0;

							Entity target = getEntityPlayerIsLookingAtServer(player, 5.0);

							if (target instanceof AbstractBoatEntity boat) {
								trackedBoats.put(
										boat.getUuid(),
										boat.getDamageWobbleStrength()
								);

								source.sendFeedback(() ->
												makePrefix()
														.append(Text.literal("tracking")
																.setStyle(Style.EMPTY.withColor(
																		TextColor.fromFormatting(Formatting.GRAY))))
														.append(Text.literal(": ")
																.setStyle(Style.EMPTY.withColor(
																		TextColor.fromFormatting(Formatting.DARK_GRAY))))
														.append(Text.literal(boat.getUuid().toString())
																.setStyle(Style.EMPTY.withColor(
																		TextColor.fromFormatting(Formatting.YELLOW)))),
										true
								);
								return 1;
							} else {
								source.sendFeedback(() ->
												makePrefix()
														.append(Text.literal("that's not a boat!")
																.setStyle(Style.EMPTY.withColor(
																		TextColor.fromFormatting(Formatting.GRAY)))),
										false
								);
								return 0;
							}
						})
		);

		// /boat (no args)
		boatCommand.executes(ctx -> {
			ctx.getSource().sendFeedback(() ->
							makePrefix()
									.append(Text.literal("that's not a boat!")
											.setStyle(Style.EMPTY.withColor(
													TextColor.fromFormatting(Formatting.GRAY)))),
					false
			);
			return 0;
		});

		dispatcher.register(boatCommand);
	}

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
	onlyLookedAtUuidSuggestion(
			CommandContext<ServerCommandSource> ctx,
			SuggestionsBuilder builder
	) {
		ServerCommandSource source = ctx.getSource();

		if (!(source.getEntity() instanceof PlayerEntity player)) {
			return builder.buildFuture();
		}

		Entity target = getEntityPlayerIsLookingAtServer(player, 5.0);

		if (target != null) {
			builder.suggest(target.getUuid().toString());
		}

		return builder.buildFuture();
	}

	private static Entity getEntityPlayerIsLookingAtServer(PlayerEntity player, double distance) {
		Vec3d start = player.getCameraPosVec(1.0f);
		Vec3d direction = player.getRotationVec(1.0f);
		Vec3d end = start.add(direction.multiply(distance));

		EntityHitResult hit = ProjectileUtil.raycast(
				player,
				start,
				end,
				player.getBoundingBox().stretch(direction.multiply(distance)).expand(1.0),
				entity -> entity != player,
				distance * distance
		);

		if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
			return hit.getEntity();
		}
		return null;
	}

	// private AbstractBoatEntity getBoatPlayerIsLookingAt(PlayerEntity player, double maxDistance) {
	// 	List<AbstractBoatEntity> boats =
	// 			player.getWorld().getEntitiesByClass(
	// 					AbstractBoatEntity.class,
	// 					player.getBoundingBox().expand(maxDistance),
	// 					b -> true
	// 			);

	// 	return boats.stream()
	// 			.min(Comparator.comparingDouble(b -> b.squaredDistanceTo(player)))
	// 			.orElse(null);
	// }

	private MutableText makePrefix() {
		return Text.literal("[")
				.setStyle(Style.EMPTY.withColor(
						TextColor.fromFormatting(Formatting.DARK_GRAY)))
				.append(Text.literal("boat")
						.setStyle(Style.EMPTY.withColor(
								TextColor.fromRgb(0x38FFDE))))
				.append(Text.literal("] ")
						.setStyle(Style.EMPTY.withColor(
								TextColor.fromFormatting(Formatting.DARK_GRAY))));
	}
}
