package Controller.Service;


import Controller.Enums.CommandEnum;
import Controller.GameObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;

public class GameService implements ClientObserver {
    private Logger logger = LogManager.getLogger(GameService.class);

    private GameObserver gameObserver;
    private HashMap<String, Player> playerHashMap;
    public GameService() {
        playerHashMap = new HashMap<>();
    }
    public void setGameObserver(GameObserver gameObserver) {
        this.gameObserver = gameObserver;
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
            }
        } catch (IllegalArgumentException e) {
            logger.error("Unrecognizable command received: " + message);
        }
    }

    @Override
    public void onCrash(String UUID) {
        logger.error("Player removed: " + UUID);
        playerHashMap.remove(UUID);
        gameObserver.onCrash(UUID);
    }
}