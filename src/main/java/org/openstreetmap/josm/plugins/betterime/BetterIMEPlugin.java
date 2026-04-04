// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.betterime;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.im.InputContext;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.JTable;
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
 * work correctly even when an IME is active.
 *
 * IME behavior by context:
 * - Non-text components (map view, etc.): IME disabled (shortcuts work)
 * - Tag editor editing "name"/"name:zh"/"name:zh-Hans"/"name:zh-Hant": IME enabled (Chinese)
 * - F3 "Search presets" dialog: IME enabled (Chinese)
 * - All other text fields: IME unlocked but not switched (user controls)
 *
 * Also disables the default Ctrl+Space shortcut ("Search menu items")
 * which conflicts with Chinese input method toggling on most systems.
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
                SwingUtilities.invokeLater(this::disableCtrlSpaceShortcut);
                return;
            }

            JComponent contentPane = (JComponent) frame.getContentPane();
            KeyStroke ctrlSpace = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);

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
     *
     * Logic:
     *   1. Non-text component → disableIME (shortcuts work)
     *   2. Text in tag editor editing name/name:zh/name:zh-Hans/name:zh-Hant → enableIME
     *   3. Text in F3 TaggingPresetSearchDialog → enableIME
     *   4. All other text fields → unlockIME (user controls IME state)
     */
    private static class FocusChangeListener implements PropertyChangeListener {

        /** Tag keys that should trigger Chinese IME activation */
        private static final String[] CHINESE_TAG_KEYS = {
            "name", "name:zh", "name:zh-Hans", "name:zh-Hant"
        };

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            Component newFocus = (Component) evt.getNewValue();
            if (newFocus == null) {
                return;
            }

            boolean isTextInput = isTextInputComponent(newFocus);

            try {
                if (!isTextInput) {
                    disableIME(newFocus);
                } else if (shouldEnableChineseIME(newFocus)) {
                    enableIME(newFocus);
                } else {
                    unlockIME(newFocus);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "[BetterIME] Could not toggle IME", e);
            }
        }

        /**
         * Determines if this text component should actively enable Chinese IME.
         * Returns true for:
         *   - Tag editor editing name/name:zh/name:zh-Hans/name:zh-Hant
         *   - F3 TaggingPresetSearchDialog
         */
        private static boolean shouldEnableChineseIME(Component comp) {
            // Check F3 preset search dialog first (cheapest check)
            if (isInPresetSearchDialog(comp)) {
                return true;
            }

            // Check if editing a Chinese name tag
            String tagKey = detectTagKey(comp);
            if (tagKey != null) {
                for (String key : CHINESE_TAG_KEYS) {
                    if (key.equals(tagKey)) {
                        Logging.debug("[BetterIME] Chinese name tag detected: {0}", tagKey);
                        return true;
                    }
                }
                Logging.debug("[BetterIME] Non-Chinese tag: {0}, using unlock mode", tagKey);
            }

            return false;
        }

        // ======================================================================
        // Context detection: F3 Preset Search Dialog
        // ======================================================================

        /**
         * Detects if the component is inside the F3 "Search presets" dialog.
         * Class: TaggingPresetSearchDialog (NOT SearchDialog which is Ctrl+F)
         */
        private static boolean isInPresetSearchDialog(Component comp) {
            try {
                Window window = SwingUtilities.getWindowAncestor(comp);
                if (window != null) {
                    String className = window.getClass().getSimpleName();
                    if (className.equals("TaggingPresetSearchDialog")) {
                        Logging.debug("[BetterIME] In F3 preset search dialog");
                        return true;
                    }
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error checking preset search dialog: {0}", e.getMessage());
            }
            return false;
        }

        // ======================================================================
        // Context detection: Tag key extraction
        // ======================================================================

        /**
         * Attempts to detect the tag key being edited.
         * Handles three JOSM contexts:
         *   A) EditTagDialog popup (Properties panel double-click)
         *   B) TagTable inline editing (Relation editor)
         *   C) Preset dialog fields (after F3 preset selection)
         *
         * @return the tag key string, or null if not in a tag editing context
         */
        private static String detectTagKey(Component comp) {
            String key;

            // Context A: EditTagDialog / AbstractTagsDialog popup
            key = detectTagKeyFromEditDialog(comp);
            if (key != null) return key;

            // Context B: TagTable inline editing
            key = detectTagKeyFromTagTable(comp);
            if (key != null) return key;

            // Context C: Preset dialog fields
            key = detectTagKeyFromPresetDialog(comp);
            if (key != null) return key;

            return null;
        }

        /**
         * Context A: Detect tag key from EditTagDialog / AbstractTagsDialog.
         *
         * When user double-clicks a tag in the Properties panel, JOSM opens
         * an EditTagDialog (extends AbstractTagsDialog extends ExtendedDialog).
         * The dialog stores the tag key in a field named "key" (type String,
         * or "keys" (type String[]) in some versions).
         *
         * We walk up to the dialog window and read this field via reflection.
         */
        private static String detectTagKeyFromEditDialog(Component comp) {
            try {
                Window window = SwingUtilities.getWindowAncestor(comp);
                if (window == null) return null;

                // Check class hierarchy for EditTagDialog or AbstractTagsDialog
                Class<?> clazz = window.getClass();
                while (clazz != null) {
                    String name = clazz.getSimpleName();
                    if (name.contains("EditTagDialog") || name.contains("AbstractTagsDialog") ||
                        name.contains("TagEditHelper")) {
                        // Try to read "key" field (single key being edited)
                        String tagKey = getFieldAsString(window, clazz, "key");
                        if (tagKey != null) {
                            Logging.debug("[BetterIME] EditTagDialog tag key: {0}", tagKey);
                            return tagKey;
                        }
                        break;
                    }
                    clazz = clazz.getSuperclass();
                }

                // Also check enclosing class names (EditTagDialog is inner class of TagEditHelper)
                String windowClassName = window.getClass().getName();
                if (windowClassName.contains("TagEditHelper") || windowClassName.contains("EditTagDialog")) {
                    // Search all superclasses for "key" field
                    Class<?> c = window.getClass();
                    while (c != null && c != Object.class) {
                        String tagKey = getFieldAsString(window, c, "key");
                        if (tagKey != null) {
                            Logging.debug("[BetterIME] TagEditHelper dialog tag key: {0}", tagKey);
                            return tagKey;
                        }
                        c = c.getSuperclass();
                    }
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error detecting tag key from EditDialog: {0}", e.getMessage());
            }
            return null;
        }

        /**
         * Context B: Detect tag key from TagTable inline editing.
         *
         * In the Relation editor, tags are edited inline in a TagTable (JTable).
         * We find the JTable ancestor, get the editing row, then extract the
         * TagModel from the table model and call getName().
         */
        private static String detectTagKeyFromTagTable(Component comp) {
            try {
                // Walk up to find a JTable
                Container parent = comp.getParent();
                while (parent != null) {
                    if (parent instanceof JTable) {
                        JTable table = (JTable) parent;
                        String tableClassName = table.getClass().getSimpleName();

                        // Only proceed if this looks like a TagTable
                        if (!tableClassName.contains("TagTable") &&
                            !table.getClass().getName().contains("tagging")) {
                            parent = parent.getParent();
                            continue;
                        }

                        int editingRow = table.getEditingRow();
                        if (editingRow < 0) break;

                        // Get the value at editing row, column 0 (key column)
                        // In TagEditorModel, getValueAt returns a TagModel object
                        Object value = table.getModel().getValueAt(editingRow, 0);
                        if (value == null) break;

                        // If value is a String, it's the key directly
                        if (value instanceof String) {
                            Logging.debug("[BetterIME] TagTable key (String): {0}", value);
                            return (String) value;
                        }

                        // If value is a TagModel, call getName() via reflection
                        try {
                            Method getName = value.getClass().getMethod("getName");
                            Object result = getName.invoke(value);
                            if (result instanceof String) {
                                Logging.debug("[BetterIME] TagTable key (TagModel): {0}", result);
                                return (String) result;
                            }
                        } catch (NoSuchMethodException e) {
                            // Not a TagModel, try toString()
                            String str = value.toString();
                            if (!str.isEmpty()) {
                                Logging.debug("[BetterIME] TagTable key (toString): {0}", str);
                                return str;
                            }
                        }
                        break;
                    }
                    parent = parent.getParent();
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error detecting tag key from TagTable: {0}", e.getMessage());
            }
            return null;
        }

        /**
         * Context C: Detect tag key from Preset dialog fields.
         *
         * When a preset is applied, text fields may have a "hint" set via
         * JosmTextField.setHint() that stores the tag key.
         */
        private static String detectTagKeyFromPresetDialog(Component comp) {
            try {
                // Check if the window is a preset-related dialog
                Window window = SwingUtilities.getWindowAncestor(comp);
                if (window == null) return null;

                String windowClass = window.getClass().getName();
                if (!windowClass.contains("Preset") && !windowClass.contains("tagging")) {
                    return null;
                }

                // Try to call getHint() on the component (JosmTextField stores tag key as hint)
                try {
                    Method getHint = comp.getClass().getMethod("getHint");
                    Object hint = getHint.invoke(comp);
                    if (hint instanceof String && !((String) hint).isEmpty()) {
                        Logging.debug("[BetterIME] Preset dialog hint key: {0}", hint);
                        return (String) hint;
                    }
                } catch (NoSuchMethodException e) {
                    // Not a JosmTextField, no hint available
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error detecting tag key from preset dialog: {0}", e.getMessage());
            }
            return null;
        }

        // ======================================================================
        // Utility methods
        // ======================================================================

        /**
         * Reads a String field from an object via reflection.
         */
        private static String getFieldAsString(Object obj, Class<?> clazz, String fieldName) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value instanceof String) {
                    return (String) value;
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Field not found in this class, will try superclass
            } catch (Exception e) {
                Logging.trace("[BetterIME] Error reading field {0}: {1}", fieldName, e.getMessage());
            }
            return null;
        }

        // ======================================================================
        // Component type detection
        // ======================================================================

        /**
         * Determines whether a component is a text input that needs IME.
         */
        private static boolean isTextInputComponent(Component comp) {
            if (comp instanceof JTextComponent) {
                return true;
            }
            if (comp instanceof JComboBox) {
                return ((JComboBox<?>) comp).isEditable();
            }
            if (comp instanceof JSpinner) {
                return true;
            }
            // Some combo boxes put focus on an inner editor component.
            Component parent = comp.getParent();
            for (int i = 0; i < 3 && parent != null; i++) {
                if (parent instanceof JComboBox && ((JComboBox<?>) parent).isEditable()) {
                    return true;
                }
                parent = parent.getParent();
            }
            return false;
        }

        // ======================================================================
        // IME control methods
        // ======================================================================

        /**
         * Enable IME: actively switch to Chinese input method.
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
                Logging.trace("[BetterIME] setCompositionEnabled not supported, relying on enableInputMethods");
            }
        }

        /**
         * Unlock IME: allow input methods but force English mode.
         * Enables input method support so user can manually switch,
         * but actively selects English locale to override any residual
         * Chinese IME state from previous focus.
         *
         * Uses invokeLater to ensure the locale switch takes effect
         * AFTER the input method is fully re-activated.
         */
        private static void unlockIME(Component comp) {
            comp.enableInputMethods(true);
            SwingUtilities.invokeLater(() -> {
                try {
                    InputContext ic = comp.getInputContext();
                    if (ic != null) {
                        ic.endComposition();
                        // selectInputMethod(ENGLISH) forces the IME to English mode,
                        // unlike setCompositionEnabled(false) which only closes the
                        // composition window but leaves the IME in Chinese mode.
                        ic.selectInputMethod(Locale.ENGLISH);
                    }
                } catch (Exception e) {
                    // Fallback: try setCompositionEnabled
                    try {
                        InputContext ic = comp.getInputContext();
                        if (ic != null && ic.isCompositionEnabled()) {
                            ic.setCompositionEnabled(false);
                        }
                    } catch (Exception ignored) {
                        // Give up gracefully
                    }
                    Logging.trace("[BetterIME] selectInputMethod fallback in unlockIME: {0}", e.getMessage());
                }
            });
            Logging.debug("[BetterIME] IME unlocked (English) for: {0}", comp.getClass().getSimpleName());
        }

        /**
         * Disable IME: prevent input methods from intercepting keystrokes.
         * Also forces English locale so next enableInputMethods(true) won't
         * restore Chinese mode.
         */
        private static void disableIME(Component comp) {
            try {
                InputContext ic = comp.getInputContext();
                if (ic != null) {
                    ic.endComposition();
                    ic.selectInputMethod(Locale.ENGLISH);
                }
            } catch (Exception e) {
                Logging.trace("[BetterIME] selectInputMethod failed in disableIME: {0}", e.getMessage());
            }
            comp.enableInputMethods(false);
            Logging.debug("[BetterIME] IME disabled for: {0}", comp.getClass().getSimpleName());
        }
    }
}
