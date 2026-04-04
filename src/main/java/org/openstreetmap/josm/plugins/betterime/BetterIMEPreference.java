// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.betterime;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.tools.GBC;

/**
 * Preferences panel for BetterIME plugin.
 * Appears as a tab in JOSM's F12 Preferences dialog.
 */
public class BetterIMEPreference extends DefaultTabPreferenceSetting {

    private JCheckBox cbAutoToggle;
    private JCheckBox cbPresetSearch;
    private JCheckBox cbTagDetection;
    private DefaultListModel<String> tagListModel;

    public BetterIMEPreference() {
        super("dialogs/betterime",
              tr("BetterIME"),
              tr("Configure automatic Chinese IME switching behavior"));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel panel = new JPanel(new GridBagLayout());

        // --- Master switch ---
        cbAutoToggle = new JCheckBox(tr("Enable automatic IME switching"));
        cbAutoToggle.setSelected(BetterIMEPlugin.PROP_AUTO_TOGGLE.get());
        panel.add(cbAutoToggle, GBC.eol().fill(GridBagConstraints.HORIZONTAL).insets(0, 0, 0, 5));

        // --- F3 preset search ---
        cbPresetSearch = new JCheckBox(tr("Enable Chinese IME in F3 preset search dialog"));
        cbPresetSearch.setSelected(BetterIMEPlugin.PROP_PRESET_SEARCH.get());
        panel.add(cbPresetSearch, GBC.eol().fill(GridBagConstraints.HORIZONTAL).insets(0, 0, 0, 5));

        // --- Tag detection ---
        cbTagDetection = new JCheckBox(tr("Enable Chinese IME based on tag key detection"));
        cbTagDetection.setSelected(BetterIMEPlugin.PROP_TAG_DETECTION.get());
        panel.add(cbTagDetection, GBC.eol().fill(GridBagConstraints.HORIZONTAL).insets(0, 0, 0, 5));

        // --- Tag key list ---
        JPanel tagPanel = new JPanel(new GridBagLayout());
        tagPanel.setBorder(BorderFactory.createTitledBorder(
                tr("Tag keys that trigger Chinese IME")));

        tagListModel = new DefaultListModel<>();
        for (String key : BetterIMEPlugin.PROP_CHINESE_TAG_KEYS.get()) {
            tagListModel.addElement(key);
        }
        JList<String> tagList = new JList<>(tagListModel);
        tagList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tagList.setVisibleRowCount(6);
        JScrollPane scrollPane = new JScrollPane(tagList);
        tagPanel.add(scrollPane, GBC.std().fill(GridBagConstraints.BOTH)
                .weight(1, 1).insets(5, 5, 5, 5));

        // Buttons column
        JPanel btnPanel = new JPanel(new GridBagLayout());
        JButton btnAdd = new JButton(tr("Add"));
        JButton btnRemove = new JButton(tr("Remove"));
        JButton btnReset = new JButton(tr("Reset"));

        btnAdd.addActionListener(e -> {
            String input = JOptionPane.showInputDialog(panel,
                    tr("Enter tag key (e.g. name:en):"), tr("Add tag key"),
                    JOptionPane.PLAIN_MESSAGE);
            if (input != null && !input.trim().isEmpty()) {
                String key = input.trim();
                if (!tagListModel.contains(key)) {
                    tagListModel.addElement(key);
                }
            }
        });

        btnRemove.addActionListener(e -> {
            int idx = tagList.getSelectedIndex();
            if (idx >= 0) tagListModel.remove(idx);
        });

        btnReset.addActionListener(e -> {
            tagListModel.clear();
            for (String key : BetterIMEPlugin.DEFAULT_TAG_KEYS) {
                tagListModel.addElement(key);
            }
        });

        btnPanel.add(btnAdd, GBC.eol().fill(GridBagConstraints.HORIZONTAL).insets(0, 0, 0, 3));
        btnPanel.add(btnRemove, GBC.eol().fill(GridBagConstraints.HORIZONTAL).insets(0, 0, 0, 3));
        btnPanel.add(btnReset, GBC.eol().fill(GridBagConstraints.HORIZONTAL));
        btnPanel.add(Box.createVerticalGlue(), GBC.eol().fill(GridBagConstraints.VERTICAL));

        tagPanel.add(btnPanel, GBC.eol().anchor(GridBagConstraints.NORTH).insets(0, 5, 5, 5));

        panel.add(tagPanel, GBC.eol().fill(GridBagConstraints.BOTH).weight(1, 1));

        // Master switch controls sub-options
        cbAutoToggle.addActionListener(e -> {
            boolean on = cbAutoToggle.isSelected();
            cbPresetSearch.setEnabled(on);
            cbTagDetection.setEnabled(on);
            tagList.setEnabled(on && cbTagDetection.isSelected());
            btnAdd.setEnabled(on && cbTagDetection.isSelected());
            btnRemove.setEnabled(on && cbTagDetection.isSelected());
            btnReset.setEnabled(on && cbTagDetection.isSelected());
        });
        cbTagDetection.addActionListener(e -> {
            boolean on = cbAutoToggle.isSelected() && cbTagDetection.isSelected();
            tagList.setEnabled(on);
            btnAdd.setEnabled(on);
            btnRemove.setEnabled(on);
            btnReset.setEnabled(on);
        });

        createPreferenceTabWithScrollPane(gui, panel);
    }

    @Override
    public boolean ok() {
        BetterIMEPlugin.PROP_AUTO_TOGGLE.put(cbAutoToggle.isSelected());
        BetterIMEPlugin.PROP_PRESET_SEARCH.put(cbPresetSearch.isSelected());
        BetterIMEPlugin.PROP_TAG_DETECTION.put(cbTagDetection.isSelected());

        List<String> tags = new ArrayList<>();
        for (int i = 0; i < tagListModel.size(); i++) {
            tags.add(tagListModel.get(i));
        }
        BetterIMEPlugin.PROP_CHINESE_TAG_KEYS.put(tags);
        return false; // no restart needed
    }

    @Override
    public boolean isExpert() {
        return false;
    }
}
