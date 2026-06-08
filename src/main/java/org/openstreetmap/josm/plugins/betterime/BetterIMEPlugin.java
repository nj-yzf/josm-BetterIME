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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.ListProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

/**
 * BetterIME Plugin for JOSM.
 *
 * Context-aware Chinese IME control:
 * - Non-text components (map view, etc.): composition disabled (shortcuts work)
 * - Matching Chinese IME scenarios (see DETECTORS): composition enabled
 * - All other text fields: composition disabled (user can toggle manually)
 *
 * Also releases the Ctrl+Space shortcut for OS IME toggling.
 */
public class BetterIMEPlugin extends Plugin {

    private static final Logger LOG = Logger.getLogger(BetterIMEPlugin.class.getName());

    // ======================================================================
    // User-configurable preferences (visible in F12 and Advanced Preferences)
    // ======================================================================

    /** Master switch: enable/disable automatic IME toggling. */
    static final BooleanProperty PROP_AUTO_TOGGLE =
            new BooleanProperty("betterime.auto_toggle", true);

    /** Whether to enable Chinese IME in F3 preset search dialog. */
    static final BooleanProperty PROP_PRESET_SEARCH =
            new BooleanProperty("betterime.preset_search", true);

    /** Whether to enable Chinese IME based on tag key detection. */
    static final BooleanProperty PROP_TAG_DETECTION =
            new BooleanProperty("betterime.tag_detection", true);

    /** Default tag keys list. */
    static final List<String> DEFAULT_TAG_KEYS = Arrays.asList(
            "name", "name:zh", "name:zh-Hans", "name:zh-Hant",
            "alt_name", "operator"
    );

    /** Tag keys that trigger Chinese IME activation. */
    static final ListProperty PROP_CHINESE_TAG_KEYS =
            new ListProperty("betterime.chinese_tag_keys", DEFAULT_TAG_KEYS);

    public BetterIMEPlugin(PluginInformation info) {
        super(info);
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("permanentFocusOwner", new FocusChangeListener());
        SwingUtilities.invokeLater(this::disableCtrlSpaceShortcut);
        Logging.info("[BetterIME] Plugin loaded.");
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new BetterIMEPreference();
    }

    private void disableCtrlSpaceShortcut() {
        try {
            JFrame frame = MainApplication.getMainFrame();
            if (frame == null) {
                SwingUtilities.invokeLater(this::disableCtrlSpaceShortcut);
                return;
            }
            JComponent root = (JComponent) frame.getContentPane();
            KeyStroke ctrlSpace = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlSpace, "betterIME.noop");
            root.getActionMap().put("betterIME.noop", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { /* let OS handle */ }
            });
            Logging.info("[BetterIME] Ctrl+Space shortcut released.");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[BetterIME] Failed to release Ctrl+Space", e);
        }
    }

    // ======================================================================
    // Chinese IME detector interface — add new scenarios here
    // ======================================================================

    /** A detector that decides whether Chinese IME should be activated. */
    @FunctionalInterface
    private interface ChineseIMEDetector {
        boolean test(Component comp);
    }

    /** Ordered list of detectors. First match wins. Add new scenarios here. */
    private static final ChineseIMEDetector[] DETECTORS = {
        FocusChangeListener::isInPresetSearchDialog,
        FocusChangeListener::isEditingChineseNameTag,
    };

    // ======================================================================
    // Focus listener
    // ======================================================================

    private static class FocusChangeListener implements PropertyChangeListener {

        /** Reflection field cache: (Class, fieldName) → Field. */
        private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

        /** Whether the previous focus was in a Chinese IME context. */
        private boolean wasChinese;

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            // Master switch off → do nothing
            if (!PROP_AUTO_TOGGLE.get()) return;

            // Clean up: switch old component back to English when leaving Chinese context
            if (wasChinese) {
                Component old = (Component) evt.getOldValue();
                if (old != null) setComposition(old, false);
            }

            Component comp = (Component) evt.getNewValue();
            if (comp == null) { wasChinese = false; return; }

            if (isTagKeyInput(comp)) {
                wasChinese = false;
                setComposition(comp, false);
            } else if (!isTextInput(comp)) {
                wasChinese = false;
                setComposition(comp, false);
            } else if (shouldEnableChinese(comp)) {
                wasChinese = true;
                setComposition(comp, true);
            } else {
                wasChinese = false;
                setComposition(comp, false);
            }
        }
// PLACEHOLDER_METHODS

        /** Runs all detectors in order. First match wins. */
        private static boolean shouldEnableChinese(Component comp) {
            for (ChineseIMEDetector d : DETECTORS) {
                if (d.test(comp)) return true;
            }
            return false;
        }

        // --- Detector: F3 TaggingPresetSearchDialog ---

        static boolean isInPresetSearchDialog(Component comp) {
            if (!PROP_PRESET_SEARCH.get()) return false;
            Window w = SwingUtilities.getWindowAncestor(comp);
            return w != null && "TaggingPresetSearchDialog".equals(w.getClass().getSimpleName());
        }

        // --- Detector: editing Chinese name tags (configurable list) ---

        static boolean isEditingChineseNameTag(Component comp) {
            if (!PROP_TAG_DETECTION.get()) return false;
            String key = detectTagKey(comp);
            if (key != null) {
                Set<String> tagKeys = new HashSet<>(PROP_CHINESE_TAG_KEYS.get());
                if (tagKeys.contains(key)) {
                    Logging.debug("[BetterIME] Chinese tag: {0}", key);
                    return true;
                }
            }
            return false;
        }

        // ==================================================================
        // Tag key detection (JOSM contexts)
        // ==================================================================

        private static String detectTagKey(Component comp) {
            String key = detectTagKeyFromTagsDialogControls(comp);
            if (key != null) return key;
            key = detectTagKeyFromEditDialog(comp);
            if (key != null) return key;
            key = detectTagKeyFromTagTable(comp);
            if (key != null) return key;
            return detectTagKeyFromPresetDialog(comp);
        }

        /** Context A: Properties panel Add/Edit dialogs, using the current key field text. */
        private static String detectTagKeyFromTagsDialogControls(Component comp) {
            try {
                Window window = SwingUtilities.getWindowAncestor(comp);
                if (window == null || !window.getClass().getName().contains("TagEditHelper")) return null;

                Object values = getTagsDialogField(window, "values");
                if (!isComboOrEditor(values, comp)) return null;

                Object keys = getTagsDialogField(window, "keys");
                Object text = invokeNoArg(keys, "getText");
                if (text instanceof String && !((String) text).trim().isEmpty()) {
                    return ((String) text).trim();
                }
            } catch (Exception e) {
                Logging.trace(e);
            }
            return null;
        }

        /** Context A: EditTagDialog popup (Properties panel double-click/Edit button). */
        private static String detectTagKeyFromEditDialog(Component comp) {
            try {
                Window window = SwingUtilities.getWindowAncestor(comp);
                if (window == null) return null;
                // Walk class hierarchy looking for the dialog or its enclosing helper
                for (Class<?> c = window.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    String name = c.getSimpleName();
                    if (name.contains("EditTagDialog") || name.contains("AbstractTagsDialog")
                            || name.contains("TagEditHelper")) {
                        String v = getCachedField(window, c, "key");
                        if (v != null) return v;
                    }
                }
                // Inner class check (EditTagDialog is inner class of TagEditHelper)
                String fqn = window.getClass().getName();
                if (fqn.contains("TagEditHelper") || fqn.contains("EditTagDialog")) {
                    for (Class<?> c = window.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        String v = getCachedField(window, c, "key");
                        if (v != null) return v;
                    }
                }
            } catch (Exception e) {
                Logging.trace(e);
            }
            return null;
        }
// PLACEHOLDER_MORE

        /** Context B: TagTable inline editing (Relation editor). */
        private static String detectTagKeyFromTagTable(Component comp) {
            try {
                Container parent = comp.getParent();
                while (parent != null) {
                    if (parent instanceof JTable) {
                        JTable table = (JTable) parent;
                        String cn = table.getClass().getName();
                        if (!cn.contains("TagTable") && !cn.contains("tagging")) {
                            parent = parent.getParent();
                            continue;
                        }
                        int row = table.getEditingRow();
                        if (row < 0) break;
                        Object val = table.getModel().getValueAt(row, 0);
                        if (val == null) break;
                        if (val instanceof String) return (String) val;
                        // TagModel → getName()
                        try {
                            Method m = val.getClass().getMethod("getName");
                            Object r = m.invoke(val);
                            if (r instanceof String) return (String) r;
                        } catch (NoSuchMethodException e) {
                            String s = val.toString();
                            if (!s.isEmpty()) return s;
                        }
                        break;
                    }
                    parent = parent.getParent();
                }
            } catch (Exception e) {
                Logging.trace(e);
            }
            return null;
        }

        /** Context C: Preset dialog fields (JosmTextField with hint = tag key). */
        private static String detectTagKeyFromPresetDialog(Component comp) {
            try {
                Window w = SwingUtilities.getWindowAncestor(comp);
                if (w == null) return null;
                String wn = w.getClass().getName();
                if (!wn.contains("Preset") && !wn.contains("tagging")) return null;
                try {
                    Method getHint = comp.getClass().getMethod("getHint");
                    Object hint = getHint.invoke(comp);
                    if (hint instanceof String && !((String) hint).isEmpty()) return (String) hint;
                } catch (NoSuchMethodException e) { /* not a JosmTextField */ }
            } catch (Exception e) {
                Logging.trace(e);
            }
            return null;
        }

        // ==================================================================
        // Tag key input detection
        // ==================================================================

        private static boolean isTagKeyInput(Component comp) {
            return isTagKeyInputFromTagsDialog(comp) || isTagKeyInputFromTagTable(comp);
        }

        /** JOSM properties panel Add/Edit dialogs expose separate "keys" and "values" fields. */
        private static boolean isTagKeyInputFromTagsDialog(Component comp) {
            try {
                Window window = SwingUtilities.getWindowAncestor(comp);
                if (window == null || !window.getClass().getName().contains("TagEditHelper")) return false;

                return isComboOrEditor(getTagsDialogField(window, "keys"), comp);
            } catch (Exception e) {
                Logging.trace(e);
            }
            return false;
        }

        /** TagTable-style inline editors use column 0 for keys and column 1 for values. */
        private static boolean isTagKeyInputFromTagTable(Component comp) {
            try {
                Container parent = comp.getParent();
                while (parent != null) {
                    if (parent instanceof JTable) {
                        JTable table = (JTable) parent;
                        String cn = table.getClass().getName();
                        if (!cn.contains("TagTable") && !cn.contains("tagging")) return false;
                        return table.getEditingColumn() == 0;
                    }
                    parent = parent.getParent();
                }
            } catch (Exception e) {
                Logging.trace(e);
            }
            return false;
        }

        // ==================================================================
        // Utilities
        // ==================================================================

        private static Object getTagsDialogField(Window window, String fieldName) {
            for (Class<?> c = window.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Object value = getCachedObjectField(window, c, fieldName);
                if (value != null) return value;
            }
            return null;
        }

        private static boolean isComboOrEditor(Object combo, Component comp) {
            if (combo instanceof Component && isComponentOrAncestor((Component) combo, comp)) {
                return true;
            }
            Object editor = invokeNoArg(combo, "getEditorComponent");
            return editor instanceof Component && isComponentOrAncestor((Component) editor, comp);
        }

        /** Reads a String field via reflection, with caching. */
        private static String getCachedField(Object obj, Class<?> clazz, String fieldName) {
            Object v = getCachedObjectField(obj, clazz, fieldName);
            return v instanceof String ? (String) v : null;
        }

        /** Reads an object field via reflection, with caching. */
        private static Object getCachedObjectField(Object obj, Class<?> clazz, String fieldName) {
            String cacheKey = clazz.getName() + "#" + fieldName;
            try {
                Field f = FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
                    try {
                        Field field = clazz.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        return field;
                    } catch (NoSuchFieldException e) {
                        return null;
                    }
                });
                if (f == null) return null;
                return f.get(obj);
            } catch (Exception e) {
                return null;
            }
        }

        private static Object invokeNoArg(Object obj, String methodName) {
            if (obj == null) return null;
            try {
                Method method = obj.getClass().getMethod(methodName);
                return method.invoke(obj);
            } catch (Exception e) {
                return null;
            }
        }

        private static boolean isComponentOrAncestor(Component ancestor, Component comp) {
            for (Component c = comp; c != null; c = c.getParent()) {
                if (c == ancestor) return true;
            }
            return false;
        }

        private static boolean isTextInput(Component comp) {
            if (comp instanceof JTextComponent) return true;
            if (comp instanceof JComboBox) return ((JComboBox<?>) comp).isEditable();
            if (comp instanceof JSpinner) return true;
            Component p = comp.getParent();
            for (int i = 0; i < 3 && p != null; i++) {
                if (p instanceof JComboBox && ((JComboBox<?>) p).isEditable()) return true;
                p = p.getParent();
            }
            return false;
        }

        /**
         * Sets IME composition state.
         *
         * For text components, we only use setCompositionEnabled(true/false)
         * and NEVER call enableInputMethods(false), because re-enabling it
         * later causes ImmAssociateContext detach/reattach which restores
         * the previous Chinese IME state asynchronously (race condition).
         *
         * For non-text components, we additionally call enableInputMethods(false)
         * to fully detach the IME context, preventing the user from manually
         * switching to Chinese via Shift or Ctrl+Space. This is safe because
         * we never call enableInputMethods(true) on non-text components.
         */
        private static void setComposition(Component comp, boolean enabled) {
            try {
                InputContext ic = comp.getInputContext();
                if (ic != null) {
                    if (!enabled) ic.endComposition();
                    ic.setCompositionEnabled(enabled);
                }
            } catch (UnsupportedOperationException e) {
                Logging.trace("[BetterIME] setCompositionEnabled not supported");
            }
            if (!enabled && !isTextInput(comp)) {
                comp.enableInputMethods(false);
            }
        }
    }
}
