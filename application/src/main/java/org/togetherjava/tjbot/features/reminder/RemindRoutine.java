package org.togetherjava.tjbot.features.reminder;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.togetherjava.tjbot.db.Database;
import org.togetherjava.tjbot.features.Routine;

import javax.annotation.Nullable;

import java.awt.Color;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.togetherjava.tjbot.db.generated.Tables.PENDING_REMINDERS;

/**
 * Routine that processes and sends pending reminders.
 * <p>
 * Reminders can be set by using {@link ReminderCommand}.
 */
public final class RemindRoutine implements Routine {
    static final Logger logger = LoggerFactory.getLogger(RemindRoutine.class);
    static final Color AMBIENT_COLOR = Color.decode("#F7F492");
    private static final int SCHEDULE_INTERVAL_SECONDS = 30;
    private final Database database;

    /**
     * Creates a new instance.
     *
     * @param database the database that contains the pending reminders to send.
     */
    public RemindRoutine(Database database) {
        this.database = database;
    }

    @Override
    public Schedule createSchedule() {
        return new Schedule(ScheduleMode.FIXED_RATE, 0, SCHEDULE_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @Override
    public void runRoutine(JDA jda) {
        Instant now = Instant.now();
        database.write(context -> context.selectFrom(PENDING_REMINDERS)
            .where(PENDING_REMINDERS.REMIND_AT.lessOrEqual(now))
            .stream()
            .forEach(pendingReminder -> {
                sendReminder(jda, pendingReminder.getId(), pendingReminder.getChannelId(),
                        pendingReminder.getAuthorId(), pendingReminder.getContent(),
                        pendingReminder.getCreatedAt());

                pendingReminder.delete();
            }));
    }

    private static void sendReminder(JDA jda, long id, long channelId, long authorId,
            CharSequence content, TemporalAccessor createdAt) {
        RestAction<ReminderRoute> route = computeReminderRoute(jda, channelId, authorId);
        sendReminderViaRoute(route, id, content, createdAt);
    }

    private static RestAction<ReminderRoute> computeReminderRoute(JDA jda, long channelId,
            long authorId) {
        // If guild channel can still be found, send there
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel != null) {
            return createGuildReminderRoute(jda, authorId, channel);
        }

        // Otherwise, attempt to DM the user directly
        return createDmReminderRoute(jda, authorId);
    }

    private static RestAction<ReminderRoute> createGuildReminderRoute(JDA jda, long authorId,
            MessageChannel channel) {
        return jda.retrieveUserById(authorId)
            .onErrorMap(error -> null)
            .map(author -> ReminderRoute.toPublic(channel, author));
    }

    private static RestAction<ReminderRoute> createDmReminderRoute(JDA jda, long authorId) {
        return jda.openPrivateChannelById(authorId).map(ReminderRoute::toPrivate);
    }

    private static void sendReminderViaRoute(RestAction<ReminderRoute> routeAction, long id,
            CharSequence content, TemporalAccessor createdAt) {
        Function<ReminderRoute, MessageCreateAction> sendMessage = route -> route.channel
            .sendMessageEmbeds(createReminderEmbed(content, createdAt, route.target()))
            .setContent(route.description());

        Consumer<Throwable> logFailure = failure -> logger.warn(
                """
                        Failed to send a reminder (id '{}'), skipping it. This can be due to a network issue, \
                        but also happen if the bot disconnected from the target guild and the \
                        user has disabled DMs or has been deleted.""",
                id);

        routeAction.flatMap(sendMessage).queue(doNothing(), logFailure);
    }

    private static MessageEmbed createReminderEmbed(CharSequence content,
            TemporalAccessor createdAt, @Nullable User author) {
        String authorName = author == null ? "Unknown user" : author.getAsTag();
        String authorIconUrl = author == null ? null : author.getAvatarUrl();

        return new EmbedBuilder().setAuthor(authorName, null, authorIconUrl)
            .setDescription(content)
            .setFooter("reminder from")
            .setTimestamp(createdAt)
            .setColor(AMBIENT_COLOR)
            .build();
    }

    private static <T> Consumer<T> doNothing() {
        return a -> {
        };
    }

    private record ReminderRoute(MessageChannel channel, @Nullable User target,
            @Nullable String description) {
        static ReminderRoute toPublic(MessageChannel channel, @Nullable User target) {
            return new ReminderRoute(channel, target,
                    target == null ? null : target.getAsMention());
        }

        static ReminderRoute toPrivate(PrivateChannel channel) {
            return new ReminderRoute(channel, channel.getUser(),
                    "(Sending your reminder directly, because I was unable to locate"
                            + " the original channel you wanted it to be send to)");
        }
    }
}
