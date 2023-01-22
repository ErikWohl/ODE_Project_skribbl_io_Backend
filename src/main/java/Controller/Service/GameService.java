package Controller.Service;


import Controller.Enums.CommandEnum;
import Controller.Enums.GameStateEnum;
import Controller.Enums.PlayerStateEnum;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class GameService implements ClientObserver {
    private Logger logger = LogManager.getLogger(GameService.class);

    private GameObserver gameObserver;
    private HashMap<String, Player> playerHashMap;
    private final List<String> wordList;
    private int wordCount = 3;
    private List<String> choosableWords;
    private String chosenWord;
    private Random rnd = new Random();
    public GameService() {
        playerHashMap = new HashMap<>();
        // Einbauen mehrerer Sprachen
        wordList = Arrays.asList("Test", "foo", "bar", "lorem", "ipsum");
    }
    public void setGameObserver(GameObserver gameObserver) {
        this.gameObserver = gameObserver;
    }

    private void choosingPlayerStates() {
        int drawer = rnd.nextInt(playerHashMap.size());
        List<Map.Entry<String, Player>> list = new ArrayList<>(playerHashMap.entrySet());
        Player player = list.get(drawer).getValue();

        for(int i = 0; i < playerHashMap.size(); i++) {
            if(i == drawer) {
                playerHashMap.get(list.get(i).getKey()).setPlayerState(PlayerStateEnum.DRAWER);
                logger.info("Player (" + list.get(i).getKey() + ") with username: " + list.get(i).getValue().getUsername() + " was chosen a the drawer.");
            } else {
                playerHashMap.get(list.get(i).getKey()).setPlayerState(PlayerStateEnum.GUESSER);
                logger.info("Player (" + list.get(i).getKey() + ") with username: " + list.get(i).getValue().getUsername() + " was chosen as a guesser.");
            }
        }
        logger.info("Sending guesser requests.");
        gameObserver.multicast(list.get(drawer).getKey(), CommandEnum.GUESSER_REQUEST.getCommand());
        logger.info("Sending special drawer request.");
        gameObserver.unicast(list.get(drawer).getKey(), CommandEnum.DRAWER_REQUEST.getCommand() + chooseRandomWords());
        //todo: Auch hier überprüfung auf timeout einbauen
    }
    private String chooseRandomWords() {
        choosableWords = new ArrayList<>();

        for(int i = 0; i < wordCount; i++) {
            int r = rnd.nextInt(wordList.size());
            if(!choosableWords.contains(wordList.get(r))) {
                choosableWords.add(wordList.get(r));
            } else {
                i--;
            }
        }
        return String.join(";", choosableWords);
    }
    private void sendChosenWord() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chosenWord.length(); i++) {
            sb.append('_');
        }
        String blank = sb.toString();
        for(var player : playerHashMap.entrySet()) {
            if(player.getValue().getPlayerState() == PlayerStateEnum.GUESSER) {
                logger.info("Sending guesser the blank word.");
                gameObserver.unicast(player.getKey(), CommandEnum.ROUND_STARTED.getCommand()+blank);
            } else if(player.getValue().getPlayerState() == PlayerStateEnum.DRAWER) {
                logger.info("Sending the drawer the actual word.");
                gameObserver.unicast(player.getKey(), CommandEnum.ROUND_STARTED.getCommand()+chosenWord);

            } else {
                logger.error("Player (" + player.getKey() + ") is in an illegal state.");
                throw new RuntimeException();
            }
        }
    }
    private void reset() {
        for(var player : playerHashMap.entrySet()) {
            player.getValue().resetStates();
        }
        choosableWords = null;
        chosenWord = null;
    }
    @Override
    public void onStart(String UUID) {
        logger.info("New player created: " + UUID);
        playerHashMap.put(UUID, new Player("placeholderUsername"));
    }

    @Override
    public void processMessage(String UUID, String message) {
        String command = message.substring(0, 3);

        try {
            CommandEnum commandEnum = CommandEnum.fromString(command);

            switch (commandEnum) {
                case MESSAGE:
                case CLEAR:
                case DRAWING: {
                    gameObserver.multicast(UUID, message);
                    break;
                }

                case START_GAME_REQUEST: {
                    logger.info("Trying to start a game ...");

                    for(var player : playerHashMap.entrySet()) {
                        if(player.getValue().getGameState() != GameStateEnum.INITIAL && player.getValue().getPlayerState() != PlayerStateEnum.NONE) {
                            logger.error("Player (" + player.getKey() + ") was not in the correct state.");
                            logger.error("Sending error message.");
                            gameObserver.broadcast(CommandEnum.ERROR.getCommand());
                            reset();
                            return;
                        }
                    }
                    logger.info("Sending game start request.");
                    gameObserver.broadcast(CommandEnum.START_GAME_REQUEST.getCommand());
                    break;
                }
                case START_GAME_ACKNOWLEDGEMENT: {
                    logger.info("Player (" + UUID + ") has acknowledged the game start.");
                    ReadWriteLock lock = new ReentrantReadWriteLock();
                    try {
                        lock.writeLock().lock();
                        playerHashMap.get(UUID).setGameState(GameStateEnum.STARTING);

                        int count = 0;
                        for(var player : playerHashMap.entrySet()) {
                            if(player.getValue().getGameState() == GameStateEnum.STARTING && player.getValue().getPlayerState() == PlayerStateEnum.NONE) {
                                count++;
                            }
                        }
                        if(count == playerHashMap.size()) {
                            logger.info("All players acknowledged. Choosing drawer and guessers.");
                            choosingPlayerStates();
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }

                    //todo: Abbruch, wenn zu viel Zeit vergangen ist.
                    // Timer der nach 5 sek einen error schickt? Request timed out.
                    break;
                }
                case START_GAME_NOTACKNOWLEDGEMENT: {
                    logger.error("Player (" + UUID + ") has not acknowledged the game start.");
                    gameObserver.broadcast(CommandEnum.ERROR.getCommand());
                    reset();
                    break;
                }
                case DRAWER_ACKNOWLEDGEMENT:  {
                    var player = playerHashMap.get(UUID);

                    if(player.getPlayerState() == PlayerStateEnum.DRAWER) {
                        String word = message.substring(3);
                        if(!choosableWords.contains(word)) {
                            logger.error("Drawer (" + UUID + ") did not send a correct word.");
                            logger.error("Sending error message.");
                            gameObserver.broadcast(CommandEnum.ERROR.getCommand());
                            reset();
                            break;
                        }
                        chosenWord = word;
                        logger.info("The drawer has chosen the word: " + word);
                        logger.info("Sending round start request.");
                        gameObserver.broadcast(CommandEnum.ROUND_START_REQUEST.getCommand());
                    }
                    break;
                }
                case ROUND_START_ACKNOWLEDGEMENT: {
                    logger.info("Player (" + UUID + ") has acknowledged the round start.");
                    ReadWriteLock lock = new ReentrantReadWriteLock();
                    try {
                        lock.writeLock().lock();
                        playerHashMap.get(UUID).setGameState(GameStateEnum.STARTED);

                        int count = 0;
                        for(var player : playerHashMap.entrySet()) {
                            if(player.getValue().getGameState() == GameStateEnum.STARTED && player.getValue().getPlayerState() != PlayerStateEnum.NONE) {
                                count++;
                            }
                        }
                        if(count == playerHashMap.size()) {
                            logger.info("All players acknowledged. Sending round started.");
                            sendChosenWord();
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }

                    //todo: Abbruch, wenn zu viel Zeit vergangen ist.
                    // Timer der nach 5 sek einen error schickt? Request timed out.
                    break;
                }
                case ROUND_START_NOTACKNOWLEDGEMENT: {
                    logger.error("Player (" + UUID + ") has not acknowledged the round start.");
                    gameObserver.broadcast(CommandEnum.ERROR.getCommand());
                    reset();
                    break;
                }
            }
        } catch (IllegalArgumentException e) {
            logger.error("Unrecognizable command received: " + message);
        }
    }

    @Override
    public void onCrash(String UUID) {
        logger.error("Player removed: " + UUID);
        playerHashMap.remove(UUID);
        gameObserver.broadcast(CommandEnum.ERROR.getCommand());
        reset();
        gameObserver.onCrash(UUID);
    }
}
