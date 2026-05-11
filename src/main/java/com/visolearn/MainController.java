package com.visolearn;

import com.visolearn.utils.SettingsModal;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

public class MainController {

    @FXML
    private void handleOpenSettings(ActionEvent event) {
        // Retrieve the root StackPane from the button's scene and show the modal
        StackPane root = (StackPane) ((Node) event.getSource()).getScene().getRoot();
        SettingsModal.show(root);
    }
}

