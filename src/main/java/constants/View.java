package constants;

import java.net.URL;

public enum View {
    WELCOME("/fxml/WelcomeView.fxml"),
    SEARCH("/fxml/SearchView.fxml"),
    VIDEO("/fxml/VideoView.fxml"),
    NAME("/fxml/NameView.fxml"),
    CHUNK("/fxml/ChunkView.fxml"),
    CREATION_CELL("/fxml/CreationCell.fxml"),
    IMAGE_PREVIEW("/fxml/ImagePreView.fxml");

    private final URL scene;

    View(String scene) {
        this.scene = getClass().getResource(scene);
    }

    public URL get() {
        return scene;
    }
}
