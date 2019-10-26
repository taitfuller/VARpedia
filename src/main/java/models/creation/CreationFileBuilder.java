package models.creation;

import constants.Filename;
import constants.Folder;
import constants.Music;
import constants.View;
import controllers.AdaptivePanel;
import javafx.beans.property.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import main.ProcessRunner;
import models.AsynchronousFileBuilder;
import models.FileManager;
import models.FormManager;
import models.chunk.Chunk;
import models.chunk.ChunkFileManager;
import models.images.ImageFileManager;
import org.apache.commons.text.WordUtils;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Implements a {@link AsynchronousFileBuilder} for {@link Creation} objects
 */
public class CreationFileBuilder implements AsynchronousFileBuilder<Creation> {

    public enum State {
        BUILDING,
        SUCCEEDED,
        FAILED;
    }
    private ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    // Must be set before build() is called
    private String name;
    private String searchTerm;
    private String searchText;
    private List<URL> images;
    private Music backgroundMusic;
    // Set by CreationManager
    private File creationFolder;
    // Set by FormManager
    private boolean edit;

    // Internal use
    private File combinedAudio = new File(Folder.TEMP.get(), Filename.COMBINED_AUDIO.get());
    private File backgroundAudio = new File(Folder.TEMP.get(), "background.mp3");
    private File audio = new File(Folder.TEMP.get(), "audio.wav");
    private File slideshowConfig = new File(Folder.TEMP.get(), "slideshow_config.txt");
    private File slideshowVideo = new File(Folder.TEMP.get(), "slideshow.avi");
    private File combinedVideo = new File(Folder.TEMP.get(), "combined.avi");
    private File videoFile = null;
    private File thumbnailFile = null;

    private ReadOnlyStringWrapper progressMessage = new ReadOnlyStringWrapper();


    private double backgroundMusicVolume = 0.3;
    private double imageDuration;

    /**
     * Set the name of the creation to be built
     *
     * @param name The name of the creation to be built
     * @return {@code this}
     */
    public CreationFileBuilder setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Set the search term of the creation to be built
     *
     * @param searchTerm The search term of the creation to be built
     * @return {@code this}
     */
    public CreationFileBuilder setSearchTerm(String searchTerm) {
        this.searchTerm = searchTerm;
        return this;
    }

    public CreationFileBuilder setSearchText(String searchText) {
        this.searchText = searchText;
        return this;
    }

    public CreationFileBuilder setImages(List<URL> images) {
        this.images = new ArrayList<>(images);
        return this;
    }

    public CreationFileBuilder setBackgroundMusic(Music music){
        this.backgroundMusic = music;
        return this;
    }

    public CreationFileBuilder setCreationFolder(File creationFolder) {
        this.creationFolder = creationFolder;
        videoFile = new File(creationFolder, Filename.VIDEO.get());
        thumbnailFile = new File(creationFolder, Filename.THUMBNAIL.get());
        return this;
    }

    public CreationFileBuilder setEdit(boolean edit) {
        this.edit = edit;
        return this;
    }

    public State getState() {
        return state.get();
    }
    private void setState(State state) {
        this.state.set(state);
    }
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public String getProgressMessage() {
        return progressMessage.get();
    }
    private void setProgressMessage(String progressMessage) {
        this.progressMessage.set(progressMessage);
    }
    public ReadOnlyStringProperty progressMessageProperty() {
        return progressMessage.getReadOnlyProperty();
    }

    @Override
    public void build(FileManager<Creation> caller) {
        setState(State.BUILDING);
        // TODO - Image Previewing needs to be implemented
//        images = new ArrayList<>(images.subList(0, numberOfImages));

        // TODO - Validate fields

        // TODO - Validate creation path/folder

        combineAudio();
    }

    public void combineAudio() {
        setProgressMessage("Combining Snippets...");

        ChunkFileManager chunkFileManager = ChunkFileManager.getInstance();
        List<Chunk> items = chunkFileManager.getItems();
        File combinedAudio = new File(Folder.TEMP.get(), Filename.COMBINED_AUDIO.get());
        String combineAudioCommand;
        if (items.size() == 1) {
            combineAudioCommand = String.format("mv '%s' '%s'",
                    chunkFileManager.getFile(items.get(0)), combinedAudio.getPath());
        } else {
            List<String> combineAudioCommandList = new ArrayList<>();
            combineAudioCommandList.add("sox");
            for (Chunk chunk : items) {
                combineAudioCommandList.add(String.format("'%s'", chunkFileManager.getFile(chunk)));
            }
            combineAudioCommandList.add(combinedAudio.getPath());
            combineAudioCommand = String.join(" ", combineAudioCommandList);
        }

        ProcessRunner combineAudio = new ProcessRunner(combineAudioCommand);
        new Thread(combineAudio).start();
        combineAudio.setOnSucceeded(event -> {
            calculateImageDuration();
        });
        combineAudio.setOnFailed(event -> {
//            combineAudio.getException().printStackTrace();
            setState(State.FAILED);
        });
    }

    private void calculateImageDuration() {
        setProgressMessage("Calculating duration...");

        Media media = new Media(combinedAudio.toURI().toString());

        MediaPlayer mediaPlayer = new MediaPlayer(media);
        mediaPlayer.setOnReady(() -> {
            imageDuration = (media.getDuration().toSeconds() +0.1) / images.size();

            createSlideshow();
        });
    }

    private void createSlideshow() {
        setProgressMessage("Creating video...");

        // Write config file
        try {
            FileWriter writer = new FileWriter(slideshowConfig);

            String previous = null;
            ImageFileManager imageFileManager = ImageFileManager.getInstance();
            for (URL image: images) {
                File imageFile = imageFileManager.getFile(image);
                previous = String.format("file '%s'\n", imageFile.getAbsolutePath());
                writer.write(previous);
                writer.write(String.format("duration %f\n", imageDuration));
            }
            if (previous != null) {
                writer.write(previous);
            }

            writer.close();
        } catch (IOException e) {
            setState(State.FAILED);
//            e.printStackTrace(); // TODO - Remove?
            return;
        }

        // Run command
        String cmnd = String.format(
                "ffmpeg -f concat -safe 0 -i '%s' -vsync vfr -pix_fmt yuv420p '%s' -v quiet",
                slideshowConfig.toString(), slideshowVideo.toString());
        ProcessRunner slideshowMaker = new ProcessRunner(cmnd);
        slideshowMaker.setOnSucceeded(event -> createThumbnail());
        slideshowMaker.setOnFailed(event -> {
//            slideshowMaker.getException().printStackTrace();
            setState(State.FAILED);
        });
        Executors.newSingleThreadExecutor().submit(slideshowMaker);
    }

    private void createThumbnail() {
        setProgressMessage("Creating thumbnail...");

        String command = String.format(
                "ffmpeg -i %s -vframes 1 -filter \"scale=80:60:force_original_aspect_ratio=increase,crop=80:60\" %s",
                slideshowVideo.toString(), thumbnailFile.toString());
        ProcessRunner thumbnailMaker = new ProcessRunner(command);
        thumbnailMaker.setOnSucceeded(event -> setBackgroundMusicVolume());
        thumbnailMaker.setOnFailed(event -> {
//            thumbnailMaker.getException().printStackTrace();
            setState(State.FAILED);
        });
        Executors.newSingleThreadExecutor().submit(thumbnailMaker);
    }

    private void setBackgroundMusicVolume() {
        setProgressMessage("Adding background music...");

        if (backgroundMusic != null && backgroundMusic != Music.TRACK_NONE) {
            String command = String.format("ffmpeg -i %s -filter:a \"volume=%s\" %s",
                    backgroundMusic.getMusicFile().toString(), backgroundMusicVolume, backgroundAudio.toString());
            ProcessRunner backgroundVolume = new ProcessRunner(command);
            backgroundVolume.setOnSucceeded(event -> addBackgroundMusic());
            backgroundVolume.setOnFailed(event -> {
//                backgroundVolume.getException().printStackTrace();
                setState(State.FAILED);
            });
            Executors.newSingleThreadExecutor().submit(backgroundVolume);
        } else {
            addBackgroundMusic();
        }
    }

    private void addBackgroundMusic() {
        if (backgroundMusic != null && backgroundMusic != Music.TRACK_NONE) {
            String command = String.format("ffmpeg -i %s -i %s -filter_complex amix=inputs=2:duration=shortest %s",
                    combinedAudio.toString(), backgroundAudio.toString(), audio.toString());
            ProcessRunner addBackground = new ProcessRunner(command);
            addBackground.setOnSucceeded(event -> combineVideo());
            addBackground.setOnFailed(event -> {
//                addBackground.getException().printStackTrace();
                setState(State.FAILED);
            });
            Executors.newSingleThreadExecutor().submit(addBackground);
        } else {
            combinedAudio.renameTo(audio);
            combineVideo();
        }
    }

    private void combineVideo() {
        setProgressMessage("Adding audio...");

        // Run command
        String combineCommand = String.format("ffmpeg -i '%s' -i '%s' -c copy '%s' -v quiet",
                audio.toString(), slideshowVideo.toString(), combinedVideo.toString());
        ProcessRunner combiner = new ProcessRunner(combineCommand);
        combiner.setOnSucceeded(event -> convertVideo()); //TODO - progress sending
        combiner.setOnFailed(event -> {
//            combiner.getException().printStackTrace();
            setState(State.FAILED);
        });
        Executors.newSingleThreadExecutor().submit(combiner);
    }

    private void convertVideo() {
        setProgressMessage("Saving creation...");

        // Run command
        String drawtext = String.format(
                "\"drawtext=fontfile=.bin/Montserrat-Regular.ttf:fontsize=120:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2:" +
                        "borderw=3:bordercolor=0x333333@0x33:text=%s\"", WordUtils.capitalizeFully(searchTerm));
        String cmnd = String.format("ffmpeg -i %s -vf %s -c:v libx264 -crf 19 -preset slow -c:a libfdk_aac " +
                        "-b:a 192k -ac 2  -max_muxing_queue_size 4096 %s -v quiet",
                combinedVideo.getPath(), drawtext, videoFile.toString());
        System.out.println(cmnd);
        ProcessRunner converter = new ProcessRunner(cmnd);

        // TODO - progress sending
        converter.setOnSucceeded(event -> {
            List<Chunk> chunks = new ArrayList<>(ChunkFileManager.getInstance().getItems());
            Creation creation = new Creation(name, searchTerm, searchText, chunks, images, backgroundMusic);

            if (edit) {
                CreationFileManager.getInstance().edit(creation, creationFolder, AdaptivePanel.getSelectedCreation());
            } else {
                CreationFileManager.getInstance().save(creation, creationFolder);
            }
            setState(State.SUCCEEDED);
        });
        converter.setOnFailed(event -> {
//            converter.getException().printStackTrace();
            setState(State.FAILED);
        });

        Executors.newSingleThreadExecutor().submit(converter);
    }
}