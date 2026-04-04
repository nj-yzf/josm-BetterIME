// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.betterime;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.im.InputContext;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

/**
 * BetterIME Plugin for JOSM.
 *
 * Automatically disables Chinese input method (IME) when focus is on
 * non-text components (like the map view), so that keyboard shortcuts
 * work correctly even when an IME is active. When the user clicks
 * into a text field or text area, the IME is re-enabled for normal
 * text input.
 *
 * Also disables the default Ctrl+Space shortcut ("Search menu items")
 * which conflicts with Chinese input method toggling on most systems.
 *
 * This solves a common pain point for Chinese users where the IME
 * intercepts shortcut keys like 'S', 'A', 'D', etc.
 */
public class BetterIMEPlugin extends Plugin {

    private static final Logger LOG = Logger.getLogger(BetterIMEPlugin.class.getName());

    private final FocusChangeListener focusListener;

    /**
     * Plugin constructor — called by JOSM when loading the plugin.
     *
     * @param info plugin metadata
     */
    public BetterIMEPlugin(PluginInformation info) {
        super(info);

        focusListener = new FocusChangeListener();

        // Register global focus listener
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("permanentFocusOwner", focusListener);

        // Disable Ctrl+Space shortcut (conflicts with Chinese IME toggle).
        // Use invokeLater because the main frame may not exist yet during plugin init.
        SwingUtilities.invokeLater(this::disableCtrlSpaceShortcut);

        Logging.info("[BetterIME] Plugin loaded. IME will be auto-toggled based on focus.");
    }

    /**
     * Removes the Ctrl+Space keybinding from JOSM's main window.
     *
     * JOSM binds Ctrl+Space to "Search menu items" (MenuItemSearchDialog),
     * but this key combo is used by most Chinese input methods to toggle IME
     * on/off. We replace it with a no-op action so the OS IME toggle works.
     */
    private void disableCtrlSpaceShortcut() {
        try {
            JFrame frame = MainApplication.getMainFrame();
            if (frame == null) {
                Logging.warn("[BetterIME] Main frame not ready, retrying Ctrl+Space removal...");
                // Retry after a short delay — main frame may still be initializing
                SwingUtilities.invokeLater(this::disableCtrlSpaceShortcut);
                return;
            }

            JComponent contentPane = (JComponent) frame.getContentPane();
            KeyStroke ctrlSpace = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);

            // Replace the Ctrl+Space binding with a do-nothing action.
            // We use a named no-op action rather than "none" because "none"
            // only works for WHEN_FOCUSED scope, not WHEN_IN_FOCUSED_WINDOW.
            String actionKey = "betterIME.consumeCtrlSpace";
            contentPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlSpace, actionKey);
            contentPane.getActionMap().put(actionKey, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Intentionally empty — let the OS handle Ctrl+Space for IME toggling
                }
            });

            Logging.info("[BetterIME] Ctrl+Space shortcut disabled (was: Search menu items).");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[BetterIME] Failed to disable Ctrl+Space shortcut", e);
        }
    }

    /**
     * Listens for focus changes and toggles IME accordingly.
     */
    private static class FocusChangeListener implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            Component newFocus = (Component) evt.getNewValue();
            if (newFocus == null) {
                return;
            }

            boolean isTextInput = isTextInputComponent(newFocus);

            try {
                if (isTextInput) {
                    // Check if editing a Chinese name tag
                    if (isChineseNameTag(newFocus)) {
                        enableIME(newFocus);
                    } else {
                        // Other text fields: unlock but don't switch IME
                        unlockIME(newFocus);
                    }
                } else {
                    disableIME(newFocus);
                }
            } catch (Exception e) {
                // Some platforms/JVMs may not support IME control fully.
                // Log and continue — never crash JOSM over IME issues.
                LOG.log(Level.FINE, "[BetterIME] Could not toggle IME", e);
            }
        }

        /**
         * Detects if the component is editing one of the Chinese name tags.
         * Checks for: "name", "name:zh", "name:zh-Hans", "name:zh-Hant"
         */
        private static boolean isChineseNameTag(Component comp) {
            try {
                // Must be in tag editor first
                if (!isInTagEditor(comp)) {
                    return false;
                }

                // Try to extract the tag key from the component hierarchy
                String tagKey = extractTagKey(comp);
                if (tagKey != null) {
                    boolean isChineseTag = tagKey.equals("name") ||
                                          tagKey.equals("name:zh") ||
                                          tagKey.equals("name:zh-Hans") ||
                                          tagKey.equals("name:zh-Hant");
                    if (isChineseTag) {
                        Logging.debug("[BetterIME] Detected Chinese name tag: {0}", tagKey);
                    }
                    return isChineseTag;
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error detecting Chinese name tag: {0}", e.getMessage());
            }
            return false;
        }

        /**
         * Extracts the tag key from a component in the tag editor.
         * Looks for component names or properties that identify the tag.
         */
        private static String extractTagKey(Component comp) {
            try {
                // Try component name first (format: "tag_<key>" or similar)
                String name = comp.getName();
                if (name != null && !name.isEmpty()) {
                    if (name.startsWith("tag_")) {
                        return name.substring(4);
                    }
                    // Sometimes the name is just the tag key
                    if (name.startsWith("name")) {
                        return name;
                    }
                }

                // Try to find tag key in parent components or their properties
                Container parent = comp.getParent();
                for (int i = 0; i < 5 && parent != null; i++) {
                    // Check if parent has a name that indicates the tag
                    String parentName = parent.getName();
                    if (parentName != null && !parentName.isEmpty()) {
                        if (parentName.startsWith("tag_")) {
                            return parentName.substring(4);
                        }
                        if (parentName.startsWith("name")) {
                            return parentName;
                        }
                    }

                    // Check for client properties (JOSM may store tag info here)
                    if (parent instanceof JComponent) {
                        JComponent jcomp = (JComponent) parent;
                        Object tagKeyProp = jcomp.getClientProperty("tagKey");
                        if (tagKeyProp != null) {
                            return tagKeyProp.toString();
                        }
                        Object tagProp = jcomp.getClientProperty("tag");
                        if (tagProp != null) {
                            return tagProp.toString();
                        }
                    }

                    parent = parent.getParent();
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error extracting tag key: {0}", e.getMessage());
            }
            return null;
        }

        /**
         * Detects if the component is inside a JOSM tag editor.
         * Traverses the parent hierarchy looking for tag editor components.
         */
        private static boolean isInTagEditor(Component comp) {
            try {
                Container parent = comp.getParent();
                for (int i = 0; i < 10 && parent != null; i++) {
                    String className = parent.getClass().getSimpleName();
                    if (className.contains("TagEditor") ||
                        className.contains("TagTable") ||
                        className.contains("TagPanel") ||
                        className.contains("TagCellEditor")) {
                        Logging.debug("[BetterIME] Detected tag editor context: {0}", className);
                        return true;
                    }
                    parent = parent.getParent();
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error detecting tag editor: {0}", e.getMessage());
            }
            return false;
        }

        /**
         * Detects if the component is inside a search dialog (F3 menu).
         * Looks for dialog windows with SearchDialog or MenuItemSearchDialog in the class name.
         */
        private static boolean isInSearchDialog(Component comp) {
            try {
                // Find the top-level window containing this component
                Component current = comp;
                while (current != null) {
                    String className = current.getClass().getSimpleName();
                    if (className.contains("SearchDialog") ||
                        className.contains("MenuItemSearchDialog")) {
                        Logging.debug("[BetterIME] Detected search dialog context: {0}", className);
                        return true;
                    }
                    if (current instanceof java.awt.Window) {
                        break;
                    }
                    current = current.getParent();
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error detecting search dialog: {0}", e.getMessage());
            }
            return false;
        }

        /**
         * Determines whether a component is a text input that needs IME.
         */
        private static boolean isTextInputComponent(Component comp) {
            // Standard Swing text components (JTextField, JTextArea, JEditorPane, etc.)
            if (comp instanceof JTextComponent) {
                return true;
            }

            // Editable combo boxes have an embedded text editor
            if (comp instanceof JComboBox) {
                return ((JComboBox<?>) comp).isEditable();
            }

            // JSpinner text editor
            if (comp instanceof JSpinner) {
                return true;
            }

            // Some combo boxes put focus on an inner editor component.
            // Walk up the parent hierarchy a few levels to check.
            Component parent = comp.getParent();
            for (int i = 0; i < 3 && parent != null; i++) {
                if (parent instanceof JComboBox && ((JComboBox<?>) parent).isEditable()) {
                    return true;
                }
                parent = parent.getParent();
            }

            return false;
        }

        /**
         * Enable IME (input method) on the given component.
         * Actively switches to Chinese input method.
         */
        private static void enableIME(Component comp) {
            comp.enableInputMethods(true);
            try {
                InputContext ic = comp.getInputContext();
                if (ic != null && !ic.isCompositionEnabled()) {
                    ic.setCompositionEnabled(true);
                    Logging.debug("[BetterIME] IME enabled for: {0}", comp.getClass().getSimpleName());
                }
            } catch (UnsupportedOperationException e) {
                // Some input methods don't support querying composition state.
                // enableInputMethods(true) already did the work.
                Logging.trace("[BetterIME] setCompositionEnabled not supported, relying on enableInputMethods");
            }
        }

        /**
         * Unlock IME (input method) on the given component without switching.
         * Preserves the user's current IME state while allowing input.
         */
        private static void unlockIME(Component comp) {
            comp.enableInputMethods(true);
            Logging.debug("[BetterIME] IME unlocked (not switched) for: {0}", comp.getClass().getSimpleName());
        }

        /**
         * Disable IME (input method) on the given component.
         */
        private static void disableIME(Component comp) {
            // First, end any ongoing composition to avoid losing partial input
            try {
                InputContext ic = comp.getInputContext();
                if (ic != null) {
                    ic.endComposition();
                    if (ic.isCompositionEnabled()) {
                        ic.setCompositionEnabled(false);
                        Logging.debug("[BetterIME] IME disabled for: {0}", comp.getClass().getSimpleName());
                    }
                }
            } catch (UnsupportedOperationException e) {
                // Fallback: some platforms don't support setCompositionEnabled.
                // enableInputMethods(false) below handles it at a lower level.
                Logging.trace("[BetterIME] setCompositionEnabled not supported, using enableInputMethods(false)");
            }
            comp.enableInputMethods(false);
        }
    }
}
