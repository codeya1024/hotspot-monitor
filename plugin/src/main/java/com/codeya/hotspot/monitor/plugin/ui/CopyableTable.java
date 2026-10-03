package com.codeya.hotspot.monitor.plugin.ui;

import javax.swing.AbstractAction;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * 支持复制的表格。
 * <ul>
 *   <li><b>Ctrl+C / ⌘+C</b>：复制当前选中区域（行选择模式下复制选中行，单元格选择模式下复制选中单元格），行内制表符分隔、行间换行分隔；</li>
 *   <li><b>双击单元格</b>：快速复制该单元格内容；</li>
 *   <li><b>右键菜单</b>：复制单元格 / 复制选中 / 复制整行 / 复制整表。</li>
 * </ul>
 * 复制内容为单元格渲染后的显示文本（与界面所见一致）。
 * <p>
 * 构造参数 {@code cellSelection=true} 时开启单元格选择（单击即选中单个单元格，⌘C 复制该格），
 * 适用于无行联动（点击行不需要触发详情）的表格；默认保持行选择模式（兼容行选择监听联动）。
 * <p>
 * 右键按下时会**选中鼠标所指的单元格**（视觉上明确"接下来复制的是哪一格"）；
 * 复制成功后通过 {@code feedback} 回调通知（如状态栏提示"✓ 已复制 …"）。
 */
public class CopyableTable extends JTable {

    /** 右键最近一次按下位置（菜单项定位鼠标所在单元格/行用） */
    private Point lastRightClick = new Point(0, 0);
    /** 右键按下时算好的 view 行列（缓存而非菜单点击时重算，避免表格每秒刷新后坐标错位） */
    private int rightClickRow = -1;
    private int rightClickCol = -1;
    /** 复制成功回调（参数 = 复制内容的单行摘要） */
    private Consumer<String> feedback;
    /** 是否开启单元格选择（右键时据此选中单格而非整行） */
    private final boolean cellSelection;
    /** 右键标记的单元格（醒目背景，不随焦点/选中态变化）；-1 = 无标记 */
    private int markRow = -1;
    private int markCol = -1;
    /** 右键标记背景色（琥珀色，Darcula 下醒目且不随失焦变淡） */
    private static final Color MARK_BG = new Color(0xC9, 0xA2, 0x27);

    public CopyableTable(TableModel model) {
        this(model, false, null);
    }

    public CopyableTable(TableModel model, boolean cellSelection) {
        this(model, cellSelection, null);
    }

    public CopyableTable(TableModel model, boolean cellSelection, Consumer<String> feedback) {
        super(model);
        this.feedback = feedback;
        this.cellSelection = cellSelection;
        if (cellSelection) {
            // 纯单元格选择：单击选中单个单元格，多选可用 Shift/⌘+点击跨行跨列
            setCellSelectionEnabled(true);
            setColumnSelectionAllowed(true);
        }
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), "hotspotCopy");
        getActionMap().put("hotspotCopy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelection();
            }
        });

        // 双击左键：复制该单元格内容
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int vr = rowAtPoint(e.getPoint());
                    int vc = columnAtPoint(e.getPoint());
                    if (vr >= 0 && vc >= 0) {
                        copyCell(vr, vc);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    lastRightClick = e.getPoint();
                    // 选中鼠标所指单元格：明确"接下来复制的是哪一格"，同时给菜单项定位
                    rightClickRow = rowAtPoint(e.getPoint());
                    rightClickCol = columnAtPoint(e.getPoint());
                    if (rightClickRow >= 0 && rightClickCol >= 0) {
                        setRowSelectionInterval(rightClickRow, rightClickRow);
                        if (cellSelection) {
                            setColumnSelectionInterval(rightClickCol, rightClickCol);
                        }
                        // 醒目标记：不依赖选中态（失焦/菜单弹出后仍可见）
                        markRow = rightClickRow;
                        markCol = rightClickCol;
                        repaint();
                    }
                }
            }
        });

        JPopupMenu pop = new JPopupMenu();
        JMenuItem copyCell = new JMenuItem("复制单元格");
        copyCell.addActionListener(e -> {
            if (rightClickRow >= 0 && rightClickCol >= 0) {
                copyCell(rightClickRow, rightClickCol);
            }
        });
        JMenuItem copySel = new JMenuItem("复制选中");
        copySel.addActionListener(e -> copySelection());
        JMenuItem copyRow = new JMenuItem("复制整行");
        copyRow.addActionListener(e -> {
            if (rightClickRow >= 0) {
                copyRow(rightClickRow);
            }
        });
        JMenuItem copyAll = new JMenuItem("复制整表");
        copyAll.addActionListener(e -> copyAll());
        pop.add(copyCell);
        pop.add(copySel);
        pop.add(copyRow);
        pop.add(copyAll);
        // 菜单关闭（无论点了哪项还是 Esc）后清除右键标记
        pop.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                markRow = -1;
                markCol = -1;
                repaint();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                markRow = -1;
                markCol = -1;
                repaint();
            }
        });
        setComponentPopupMenu(pop);
    }

    /** 复制当前选中区域（行选择=整行，单元格选择=选中单元格），TSV 格式 */
    private void copySelection() {
        int[] rows = getSelectedRows();
        int[] cols = getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            StringBuilder line = new StringBuilder();
            for (int c : cols) {
                if (line.length() > 0) {
                    line.append('\t');
                }
                line.append(cellText(r, c));
            }
            sb.append(line).append('\n');
        }
        setClipboard(sb.toString());
    }

    /** 复制单个单元格（入参为 view 行列） */
    private void copyCell(int viewRow, int viewCol) {
        if (viewRow < 0 || viewCol < 0) {
            return;
        }
        setClipboard(cellText(viewRow, viewCol));
    }

    /** 复制整行（TSV，入参为 view 行） */
    private void copyRow(int viewRow) {
        if (viewRow < 0) {
            return;
        }
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < getColumnCount(); c++) {
            if (line.length() > 0) {
                line.append('\t');
            }
            line.append(cellText(viewRow, c));
        }
        setClipboard(line.toString());
    }

    /** 复制整表（TSV，含表头） */
    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < getColumnCount(); c++) {
            if (c > 0) {
                sb.append('\t');
            }
            sb.append(getColumnName(c));
        }
        sb.append('\n');
        for (int r = 0; r < getRowCount(); r++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < getColumnCount(); c++) {
                if (line.length() > 0) {
                    line.append('\t');
                }
                line.append(cellText(r, c));
            }
            sb.append(line).append('\n');
        }
        setClipboard(sb.toString());
    }

    private void setClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(s), null);
        if (feedback != null) {
            String oneLine = s.replace('\t', ' ').replace('\n', ' ').trim();
            feedback.accept(oneLine.length() > 48 ? oneLine.substring(0, 48) + "…" : oneLine);
        }
    }

    /** 渲染时给右键标记的单元格上醒目背景色（不受失焦选中变淡影响） */
    @Override
    public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component c = super.prepareRenderer(renderer, row, column);
        if (row == markRow && column == markCol) {
            c.setBackground(MARK_BG);
        }
        return c;
    }

    /**
     * 取单元格渲染后的显示文本（与界面所见一致）。
     * 入参为 view 行列：内部自行 view→model 转换后取值，再以 view 坐标走 prepareRenderer
     * （JTable 内部会再转一次 model；若传 model 坐标会双重转换，表头排序后复制到错位行）。
     */
    private String cellText(int viewRow, int viewCol) {
        int modelRow = convertRowIndexToModel(viewRow);
        int modelCol = convertColumnIndexToModel(viewCol);
        Object v = getModel().getValueAt(modelRow, modelCol);
        if (v == null) {
            return "";
        }
        Component comp = prepareRenderer(getCellRenderer(viewRow, viewCol), viewRow, viewCol);
        if (comp instanceof JLabel) {
            String t = ((JLabel) comp).getText();
            if (t != null) {
                return t;
            }
        }
        return String.valueOf(v);
    }
}
