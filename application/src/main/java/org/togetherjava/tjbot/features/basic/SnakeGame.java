package org.togetherjava.tjbot.features.basic;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import org.togetherjava.tjbot.features.CommandVisibility;
import org.togetherjava.tjbot.features.SlashCommandAdapter;
import org.togetherjava.tjbot.features.utils.GifSequenceWriter;

import javax.imageio.ImageIO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class SnakeGame extends SlashCommandAdapter {
    private enum RunningState {
        RUNNING,
        LOST,
        WON
    }

    private record Pos(int x, int y) {
    }

    private record State(List<Pos> snake, Pos apple, RunningState runningState) {
        public State(List<Pos> snake, Pos apple) {
            this(snake, apple, RunningState.RUNNING);
        }
    }

    private enum Dir {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    private static final String NO_WIDTH_WHITESPACE = "\u200B";
    private static final int WIDTH = 25;
    private static final int HEIGHT = 15;
    private static final int TILE_SIZE = 10;
    private static final String IMAGE_FORMAT = "gif";
    private static final int TURN_PERIOD_MILLIS = 500;

    private GameMessageId gameMessageId = null;
    private State state;
    private Dir currentDirection = null;
    private int randomAppleCacheId = -1;
    private Pos randomAppleCache;
    private long timeMillis;

    public SnakeGame() {
        super("game", "Start a game", CommandVisibility.GUILD);
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event) {
        Pos apple = new Pos(ThreadLocalRandom.current().nextInt(WIDTH),
                ThreadLocalRandom.current().nextInt(HEIGHT));
        Pos head = new Pos(ThreadLocalRandom.current().nextInt(WIDTH / 4, WIDTH / 4 * 3),
                ThreadLocalRandom.current().nextInt(HEIGHT / 4, HEIGHT / 4 * 3));
        state = new State(List.of(head), apple);
        currentDirection = Dir.values()[ThreadLocalRandom.current().nextInt(Dir.values().length)];

        event.reply("Started a game").queue();

        timeMillis = event.getTimeCreated().toInstant().toEpochMilli();
        FileUpload fileUpload = generateFileFromTurns(state);

        event.getChannel()
            .sendMessage("Game")
            .setFiles(fileUpload)
            .addActionRow(createButtons(NO_WIDTH_WHITESPACE, "⬆", NO_WIDTH_WHITESPACE))
            .addActionRow(createButtons("⬅", NO_WIDTH_WHITESPACE, "➡"))
            .addActionRow(createButtons(NO_WIDTH_WHITESPACE, "⬇", NO_WIDTH_WHITESPACE))
            .queue(this::recordGameMessage);
    }

    private List<Button> createButtons(String... texts) {
        return Stream.of(texts)
            .map(input -> Button.of(ButtonStyle.SECONDARY, generateComponentId(input), input))
            .toList();
    }

    @Override
    public void onButtonClick(ButtonInteractionEvent event, List<String> args) {
        long now = event.getTimeCreated().toInstant().toEpochMilli();
        long turns = (now - timeMillis) / TURN_PERIOD_MILLIS;
        timeMillis = now;
        state = playSeveralTurns(state, (int) turns).skip(turns - 1)
            .findFirst()
            .orElse(playSeveralTurns(state, (int) turns)
                .dropWhile(s -> s.runningState() == RunningState.RUNNING)
                .findFirst()
                .orElseThrow());
        currentDirection = switch (args.get(0)) {
            case "⬆" -> Dir.UP;
            case "⬅" -> Dir.LEFT;
            case "➡" -> Dir.RIGHT;
            case "⬇" -> Dir.DOWN;
            case NO_WIDTH_WHITESPACE -> currentDirection;
            default -> throw new AssertionError("unknown");
        };
        FileUpload fileUpload = generateFileFromTurns(state);
        MessageEditData message = new MessageEditBuilder().setAttachments(fileUpload).build();

        TextChannel channel = event.getJDA().getTextChannelById(gameMessageId.channelId);

        Objects.requireNonNull(channel).editMessageById(gameMessageId.messageId, message).queue();

        event.deferEdit().queue();
    }

    private FileUpload generateFileFromTurns(State state) {
        List<BufferedImage> images = renderSeveralTurns(state, Math.max(WIDTH, HEIGHT)).toList();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
        try (var imageStream = ImageIO.createImageOutputStream(baos);
                GifSequenceWriter writer = new GifSequenceWriter(imageStream,
                        images.get(0).getType(), TURN_PERIOD_MILLIS, false)) {
            for (BufferedImage img : images) {
                writer.writeToSequence(img);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] data = baos.toByteArray();
        return FileUpload.fromData(data, "game." + IMAGE_FORMAT);
    }

    private void recordGameMessage(Message gameMessage) {
        gameMessageId = GameMessageId.fromMessage(gameMessage);
    }

    private record GameMessageId(long channelId, long messageId) {
        static GameMessageId fromMessage(Message message) {
            return new GameMessageId(message.getChannel().getIdLong(), message.getIdLong());
        }
    }

    private State next(State state) {
        List<Pos> snake = state.snake();
        Pos head = snake.get(0);
        Pos newPos = nextPos(head);
        if (newPos.x() < 0 || newPos.x() >= WIDTH || newPos.y() < 0 || newPos.y() >= HEIGHT) {
            return new State(state.snake(), state.apple(), RunningState.LOST);
        }
        if (snake.subList(1, snake.size()).contains(head)) {
            return new State(state.snake(), state.apple(), RunningState.LOST);
        }
        if (newPos.equals(state.apple())) {
            List<Pos> newSnake = Stream.concat(Stream.of(newPos), snake.stream()).toList();
            if (newSnake.size() == WIDTH * HEIGHT) {
                return new State(state.snake(), state.apple(), RunningState.WON);
            }
            if (newSnake.size() > WIDTH * HEIGHT)
                throw new AssertionError();
            return generateNewApple(new State(newSnake, state.apple));
        } else {
            List<Pos> newSnake =
                    Stream.concat(Stream.of(newPos), snake.subList(0, snake.size() - 1).stream())
                        .toList();
            return new State(newSnake, state.apple());
        }
    }

    private Pos nextPos(Pos pos) {
        return switch (currentDirection) {
            case LEFT -> new Pos(pos.x() - 1, pos.y());
            case RIGHT -> new Pos(pos.x() + 1, pos.y());
            case UP -> new Pos(pos.x(), pos.y() - 1);
            case DOWN -> new Pos(pos.x(), pos.y() + 1);
        };
    }

    private Stream<State> playSeveralTurns(State state, int turns) {
        List<State> buffer = new ArrayList<>();
        State current = state;
        buffer.add(current);
        for (int i = 0; i < turns && current.runningState() == RunningState.RUNNING; i++) {
            current = next(current);
            buffer.add(current);
        }
        return buffer.stream();
    }

    private State generateNewApple(State state) {
        List<Pos> snake = state.snake();
        if (randomAppleCacheId == snake.size()) {
            return new State(snake, randomAppleCache);
        }
        List<Pos> pos = IntStream.range(0, HEIGHT)
            .mapToObj(y -> IntStream.range(0, WIDTH).mapToObj(x -> new Pos(x, y)))
            .flatMap(s -> s)
            .filter(p -> !snake.contains(p))
            .toList();
        int i = ThreadLocalRandom.current().nextInt(pos.size());
        randomAppleCacheId = snake.size();
        randomAppleCache = pos.get(i);
        return new State(snake, randomAppleCache);
    }

    private Stream<BufferedImage> renderSeveralTurns(State state, int turns) {
        return playSeveralTurns(state, turns).map(this::render);
    }

    private BufferedImage render(State state) {
        List<Pos> snake = state.snake();
        BufferedImage img = new BufferedImage(WIDTH * TILE_SIZE, HEIGHT * TILE_SIZE,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();


        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                Pos current = new Pos(x, y);

                Color color;
                if (current.equals(state.apple())) {
                    color = Color.RED;
                } else if (current.equals(snake.get(0))) {
                    color = Color.BLACK;
                } else if (snake.contains(current)) {
                    color = Color.GRAY;
                } else {
                    color = Color.WHITE;
                }
                g.setColor(color);

                g.fillRect(x * TILE_SIZE, y * TILE_SIZE, TILE_SIZE, TILE_SIZE);
            }
        }
        if (state.runningState() == RunningState.WON) {
            g.setColor(Color.GREEN);
            drawCenteredString(g, "You won !", WIDTH * TILE_SIZE / 2F, HEIGHT * TILE_SIZE / 2F);
        } else if (state.runningState() == RunningState.LOST) {
            g.setColor(Color.RED);
            drawCenteredString(g, "You lost...", WIDTH * TILE_SIZE / 2F, HEIGHT * TILE_SIZE / 2F);
        }
        return img;
    }

    private void drawCenteredString(Graphics2D g, String string, float x, float y) {
        FontMetrics metrics = g.getFontMetrics();
        x -= metrics.stringWidth(string) / 2F;
        y += metrics.getHeight() / 2F + metrics.getAscent();
        g.drawString(string, x, y);
    }
}
