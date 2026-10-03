package com.codeya.hotspot.monitor.plugin.ui;

import com.codeya.hotspot.monitor.plugin.model.MethodNodeInfo;
import com.codeya.hotspot.monitor.plugin.model.RequestInfo;
import com.codeya.hotspot.monitor.plugin.model.SpanInfo;
import com.codeya.hotspot.monitor.plugin.model.SqlRefInfo;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 请求卡点面板，上下分栏：
 *   上半 瀑布时间线（表格：偏移 | 耗时 | 类型 | 内容 | 调用链；耗时前置、长 SQL 可横向滚动、可复制）；
 *   下半 方法调用树（JTree 可折叠；方法节点下挂载该方法内执行的 SQL，展开即见）。
 */
public class WaterfallPanel extends JPanel {

    private static final Color METHOD_COLOR = new Color(0x2ECC71);
    private static final Color SQL_COLOR = new Color(0xF5A623);
    private static final Color HTTP_COLOR = new Color(0x4A90D9);
    private static final Color ERROR_COLOR = new Color(0xD0021B);
    /** 自身耗时 > 100ms：标红（重点卡点） */
    private static final Color SELF_HOT = new Color(0xC0392B);
    /** 自身耗时 > 20ms：标橙 */
    private static final Color SELF_WARM = new Color(0xE67E22);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private final JLabel headerLabel = new JLabel();
    private final JTable timeline;
    private final DefaultTableModel timelineModel;
    private final JTree tree = new JTree();
    private final DefaultTreeModel treeModel;

    /** 上次渲染的内容指纹：每秒轮询时内容未变则跳过重建，避免界面卡顿 */
    private String lastFingerprint = "";

    /** 噪音折叠阈值（ms，来自 agent thresholds.noiseThresholdMs，默认 5）：单次 < 该值且无 SQL 的高频小调用折叠为占位行 */
    private long noiseThresholdMs = 5;

    /** 复制结果反馈（由 MainPanel 注入，显示"✓ 已复制"状态栏提示）；null 时静默 */
    private Consumer<String> feedback;

    public void setFeedback(Consumer<String> fb) {
        this.feedback = fb;
    }



    public void setNoiseThresholdMs(long ms) {
        this.noiseThresholdMs = ms > 0 ? ms : 5;
    }

    public WaterfallPanel() {
        super(new BorderLayout());

        // —— 上半：瀑布时间线（表格：偏移 | 耗时 | 类型 | 内容 | 调用链）——
        timelineModel = new DefaultTableModel(new Object[]{"偏移ms", "耗时ms", "类型", "内容", "调用链"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        timeline = new JTable(timelineModel) {
            // 点击单元格可复制内容（用户要求所有列内容可复制）
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row >= 0 && col >= 0) {
                    Object v = getValueAt(row, col);
                    if (v == null) {
                        return null;
                    }
                    // 长 SQL 等超长内容截断：完整文本巨框 tooltip 会随鼠标移动闪烁，且遮挡大半界面
                    String t = truncate(v.toString(), 300);
                    if (t.length() < v.toString().length()) {
                        t += "\n…（右键复制可查看完整内容）";
                    }
                    return t;
                }
                return null;
            }
        };
        timeline.setFont(MONO);
        timeline.setFillsViewportHeight(true);
        timeline.setRowHeight(20);
        timeline.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // 横向滚动条
        timeline.getColumnModel().getColumn(0).setPreferredWidth(70);
        timeline.getColumnModel().getColumn(1).setPreferredWidth(70);
        timeline.getColumnModel().getColumn(2).setPreferredWidth(60);
        timeline.getColumnModel().getColumn(3).setPreferredWidth(900); // 长 SQL 完整显示，超宽横向滚动
        timeline.getColumnModel().getColumn(4).setPreferredWidth(380);
        timeline.setDefaultRenderer(Object.class, new TimelineCellRenderer());
        timeline.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JPopupMenu tlPop = new JPopupMenu();
        JMenuItem tlCopy = new JMenuItem("复制选中行");
        tlCopy.addActionListener(e -> copyTimeline(false));
        JMenuItem tlCopyAll = new JMenuItem("复制全部");
        tlCopyAll.addActionListener(e -> copyTimeline(true));
        tlPop.add(tlCopy);
        tlPop.add(tlCopyAll);
        timeline.setComponentPopupMenu(tlPop);

        // —— 下半：方法调用树（可折叠；方法节点下挂 SQL 子节点）——
        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("← 选中上方请求，这里展示其方法调用树（可折叠）"));
        tree.setModel(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new MethodTreeCellRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        // 右键按下：命中节点则选中它（右键目标优先）；未命中（空白处）保留当前左键选中，
        // 让"复制选中"作用于用户已选中的节点。Swing 默认右键不改变 selection，必须显式处理。
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                    if (p != null) {
                        tree.setSelectionPath(p);
                    }
                }
            }
        });
        JPopupMenu treePop = new JPopupMenu();
        JMenuItem copySel = new JMenuItem("复制选中");
        copySel.addActionListener(e -> copySelected());
        JMenuItem copySub = new JMenuItem("复制子树");
        copySub.addActionListener(e -> copySubtree());
        JMenuItem copyAll = new JMenuItem("复制全部");
        copyAll.addActionListener(e -> copyAllTree());
        treePop.add(copySel);
        treePop.add(copySub);
        treePop.add(copyAll);
        tree.setComponentPopupMenu(treePop);

        headerLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        // 时间线区块：标题 + 瀑布表格（SQL/HTTP 行，自带独立滚动条）
        JPanel topWrap = new JPanel(new BorderLayout());
        topWrap.add(headerLabel, BorderLayout.NORTH);
        topWrap.add(new JScrollPane(timeline), BorderLayout.CENTER);

        // 上下分栏、各自独立滚动条：上半 SQL/HTTP 时间线，下半方法树
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                topWrap, new JScrollPane(tree));
        split.setResizeWeight(0.30);
        split.setDividerSize(5);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerLocation(0.30); // 默认方法栈向上调：上半 30% 时间线 / 下半 70% 方法树
        add(split, BorderLayout.CENTER);
    }



    public void setRequest(RequestInfo r) {
        String fp = fingerprint(r, noiseThresholdMs);
        if (fp.equals(lastFingerprint)) {
            return; // 内容未变化（每秒轮询常见），跳过重建
        }
        lastFingerprint = fp;
        renderTimeline(r);
        renderTree(r);
    }

    // ==================== 上半：瀑布时间线（表格） ====================

    private void renderTimeline(RequestInfo r) {
        timelineModel.setRowCount(0);
        headerLabel.setText(r == null ? " " : headerText(r));
        if (r == null) {
            return;
        }
        for (Row row : rowsOf(r)) {
            String type;
            if (!row.ok) {
                type = "■异常";
            } else if ("sql".equals(row.kind)) {
                type = "■SQL";
            } else {
                type = "■HTTP";
            }
            timelineModel.addRow(new Object[]{
                    fmt(row.offset),
                    fmt(row.nanos),
                    type,
                    row.label,                       // 完整内容（不截断）
                    row.caller == null ? "" : row.caller
            });
        }
    }

    private static String headerText(RequestInfo r) {
        return "请求: " + r.method + " " + r.path
                + "   总耗时 " + fmt(r.totalNanos) + " ms"
                + "   SQL×" + r.sqlCount + " (" + fmt(r.sqlNanos) + "ms)"
                + "   HTTP×" + r.httpCount + " (" + fmt(r.httpNanos) + "ms)"
                + "   方法" + methodSummary(r);
    }

    /** 瀑布表格单元格着色：类型列/异常行按类型上色 */
    private static final class TimelineCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                String type = String.valueOf(table.getValueAt(row, 2));
                Color fg;
                if ("■异常".equals(type)) {
                    fg = ERROR_COLOR;
                } else if ("■SQL".equals(type)) {
                    fg = column == 2 ? SQL_COLOR : table.getForeground();
                } else {
                    fg = column == 2 ? HTTP_COLOR : table.getForeground();
                }
                l.setForeground(fg);
            }
            return l;
        }
    }

    private void copyTimeline(boolean all) {
        int[] rows;
        if (all) {
            rows = new int[timelineModel.getRowCount()];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = i;
            }
        } else {
            rows = timeline.getSelectedRows();
            if (rows.length == 0) {
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            for (int c = 0; c < timelineModel.getColumnCount(); c++) {
                if (c > 0) {
                    sb.append('\t');
                }
                Object v = timelineModel.getValueAt(r, c);
                sb.append(v == null ? "" : v);
            }
            sb.append('\n');
        }
        copyToClipboard(sb.toString(), all ? "全部时间线行" : "选中行");
    }

    /** 内容指纹：任一字段变化都会导致指纹变化，从而触发重建 */
    private static String fingerprint(RequestInfo r, long noiseThresholdMs) {
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append(r.startAtMs).append('#').append(r.totalNanos).append('#')
                .append(r.sqlCount).append('#').append(r.httpCount).append('#');
        if (r.spans != null) {
            sb.append("spans=").append(r.spans.size()).append('|');
            for (SpanInfo s : r.spans) {
                sb.append(s.kind).append(':').append(s.offsetNanos).append(':')
                        .append(s.nanos).append(':').append(s.ok).append(':')
                        .append(s.label).append(':').append(s.caller == null ? "" : s.caller).append(';');
            }
        }
        sb.append('#');
        if (r.methodTree != null) {
            sb.append("tree=");
            appendTreeFp(sb, r.methodTree, noiseThresholdMs);
        }
        return sb.toString();
    }

    /** 合并 SQL/HTTP 片段，按进入偏移排序为统一时间线（方法改为下方树形展示） */
    private static List<Row> rowsOf(RequestInfo r) {
        List<Row> rows = new ArrayList<Row>();
        if (r == null) {
            return rows;
        }
        if (r.spans != null) {
            for (SpanInfo s : r.spans) {
                String tag = "sql".equals(s.kind) ? "SQL  " : "HTTP ";
                String label = tag + s.label + (s.ok ? "" : " [异常]");
                rows.add(new Row(s.kind, label, s.offsetNanos, s.nanos, s.ok, s.caller));
            }
        }
        rows.sort(Comparator.comparingLong(a -> a.offset));
        return rows;
    }

    // ==================== 下半：方法调用树（JTree） ====================

    private void renderTree(RequestInfo r) {
        if (r != null && r.methodTree != null) {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(r.methodTree);
            buildTree(root, r.methodTree.children, r.methodTree.sqls, noiseThresholdMs);
            treeModel.setRoot(root);
            tree.setRootVisible(true);
            expandToDepth(tree, 3); // 默认展开前 3 层，深层递归默认收起
        } else {
            treeModel.setRoot(new DefaultMutableTreeNode(null));
            tree.setRootVisible(false);
        }
    }

    /**
     * 构建方法调用树。单次最大耗时 < 噪音阈值且不含 SQL 的高频小调用
     * （如 LimitResultSetFilter.resultSet_next ×13073、响应写入 write ×900万）
     * 折叠为一个可展开的占位节点——树保持干净，但高频调用的合计耗时不再静默丢失；
     * 含 SQL 的方法永不折叠（SQL 来源必须可见）。
     */
    private static void buildTree(DefaultMutableTreeNode parent, List<MethodNodeInfo> children,
                                  List<SqlRefInfo> sqls, long noiseThresholdMs) {
        if (children != null) {
            FilteredGroup fg = null;
            long thresholdNs = noiseThresholdMs > 0 ? noiseThresholdMs * 1_000_000L : 0;
            for (MethodNodeInfo m : children) {
                if (thresholdNs > 0 && m.maxNanos < thresholdNs && !m.hasSql) {
                    if (fg == null) {
                        fg = new FilteredGroup();
                    }
                    fg.methods.add(m);
                    fg.totalNanos += m.totalNanos;
                    fg.calls += m.calls;
                    continue;
                }
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(m);
                parent.add(n);
                buildTree(n, m.children, m.sqls, noiseThresholdMs);
            }
            if (fg != null && !fg.methods.isEmpty()) {
                DefaultMutableTreeNode g = new DefaultMutableTreeNode(fg);
                parent.add(g);
                for (MethodNodeInfo m : fg.methods) {
                    g.add(new DefaultMutableTreeNode(m));
                }
            }
        }
        // 方法节点下挂载的 SQL：展开方法即见
        if (sqls != null) {
            for (SqlRefInfo s : sqls) {
                parent.add(new DefaultMutableTreeNode(s));
            }
        }
    }

    /** 被折叠的高频小调用占位：展开可见每个被过滤方法的聚合统计（次数/合计/单次） */
    private static final class FilteredGroup {
        final List<MethodNodeInfo> methods = new ArrayList<MethodNodeInfo>();
        long totalNanos;
        long calls;
    }

    private static void expandToDepth(JTree t, int maxDepth) {
        for (int i = 0; i < t.getRowCount(); i++) {
            TreePath p = t.getPathForRow(i);
            if (p == null) {
                continue;
            }
            if (p.getPathCount() - 1 <= maxDepth) {
                t.expandRow(i);
            }
        }
    }

    private static String nodeText(MethodNodeInfo m) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(simplify(m.name))
          .append("  ×").append(m.calls).append("次")
          .append("  自身").append(fmt(m.selfNanos)).append("ms")
          .append("  总").append(fmt(m.totalNanos)).append("ms")
          .append("  单次").append(fmtAvg(m.totalNanos / Math.max(1, m.calls)));
        if (isUnattributed(m)) {
            sb.append("  [!]");
        }
        return sb.toString();
    }

    /**
     * 自身耗时不可归因：自身耗时显著（>= 50ms）且子调用合计 < 自身耗时一半 →
     * 耗时集中在方法体内循环/内联逻辑（没有方法调用可细分），方法级插桩看不到，
     * 需要看方法实现或用 async-profiler 采样。
     */
    private static boolean isUnattributed(MethodNodeInfo m) {
        if (m.selfNanos < 50_000_000L) {
            return false;
        }
        long childTotal = 0;
        if (m.children != null) {
            for (MethodNodeInfo c : m.children) {
                childTotal += c.totalNanos;
            }
        }
        return childTotal * 2 < m.selfNanos;
    }

    /** 单次均值格式化：毫秒 / 微秒 / 纳秒自适应 */
    /** tooltip 用截断：超长 SQL/列表避免巨型黑框 + 鼠标移动闪烁 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max);
    }

    private static String fmtAvg(long nanos) {
        if (nanos >= 1_000_000L) {
            return String.format("%.2f", nanos / 1_000_000.0) + "ms";
        }
        if (nanos >= 1_000L) {
            return String.format("%.1f", nanos / 1_000.0) + "μs";
        }
        return nanos + "ns";
    }

    /** SQL 摘要：展开树行内展示前 120 字符，悬停看完整 SQL */
    private static String sqlSummary(SqlRefInfo s) {
        String sql = s.label == null ? "?" : s.label.replaceAll("\\s+", " ").trim();
        if (sql.length() > 120) {
            sql = sql.substring(0, 120) + "…";
        }
        return "SQL  " + sql + "  耗时" + fmt(s.nanos) + "ms" + (s.ok ? "" : " [异常]");
    }

    /** 树单元格渲染：方法按自身耗时着色；SQL 子节点用 SQL 色；悬停显示全限定名/完整 SQL */
    private static final class MethodTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            JLabel l = (JLabel) super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
            l.setIcon(null);
            l.setFont(MONO);
            Object uo = value instanceof DefaultMutableTreeNode
                    ? ((DefaultMutableTreeNode) value).getUserObject() : null;
            if (uo instanceof MethodNodeInfo) {
                MethodNodeInfo m = (MethodNodeInfo) uo;
                l.setText(nodeText(m));
                if (!sel) {
                    if (m.selfNanos > 100_000_000) {
                        l.setForeground(SELF_HOT);
                    } else if (m.selfNanos > 20_000_000) {
                        l.setForeground(SELF_WARM);
                    } else {
                        l.setForeground(UIManager.getColor("Tree.textForeground"));
                    }
                }
                String tip = m.name + "  自身" + fmt(m.selfNanos) + "ms / 总"
                        + fmt(m.totalNanos) + "ms / 单次最大" + fmt(m.maxNanos) + "ms ×" + m.calls + "次";
                if (isUnattributed(m)) {
                    tip += "\n[!] 自身耗时集中在方法体内循环/内联逻辑（无子调用可细分）——建议查看该方法实现或用 async-profiler 采样定位";
                }
                l.setToolTipText(tip);
            } else if (uo instanceof FilteredGroup) {
                FilteredGroup g = (FilteredGroup) uo;
                l.setText("已过滤 " + g.methods.size() + " 个高频子调用 · 合计 " + fmt(g.totalNanos)
                        + "ms · 单次均值 " + fmtAvg(g.totalNanos / Math.max(1, g.calls)) + " · 点击展开");
                if (!sel) {
                    l.setForeground(new Color(0x7F8C8D));
                }
                StringBuilder tip = new StringBuilder("以下高频小调用单次 < 噪音阈值且不含 SQL，默认折叠（展开可看明细）：\n");
                for (MethodNodeInfo m : g.methods) {
                    if (tip.length() > 600) {
                        tip.append("… 更多方法略（右键复制查看）\n");
                        break;
                    }
                    tip.append("· ").append(simplify(m.name)).append("  ×").append(m.calls)
                       .append("次  合计").append(fmt(m.totalNanos)).append("ms  单次")
                       .append(fmtAvg(m.totalNanos / Math.max(1, m.calls))).append("\n");
                }
                l.setToolTipText(tip.toString());
            } else if (uo instanceof SqlRefInfo) {
                SqlRefInfo s = (SqlRefInfo) uo;
                l.setText(sqlSummary(s));
                if (!sel) {
                    l.setForeground(s.ok ? SQL_COLOR : ERROR_COLOR);
                }
                String sqlTip = truncate(s.label == null ? "" : s.label, 300);
                if (sqlTip.length() < (s.label == null ? 0 : s.label.length())) {
                    sqlTip += "…（右键复制可查看完整 SQL）";
                }
                l.setToolTipText(sqlTip + "\n耗时 " + fmt(s.nanos) + "ms");
            } else if (uo == null) {
                l.setText("← 选中上方请求，这里展示其方法调用树（可折叠）");
            }
            return l;
        }
    }

    // ==================== 树复制（缩进文本，含 SQL 子节点） ====================

    /** 当前选中节点承载的对象：MethodNodeInfo / SqlRefInfo / FilteredGroup；无选中或非业务节点返回 null */
    private Object selectedObject() {
        TreePath p = tree.getSelectionPath();
        if (p == null) {
            return null;
        }
        return ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
    }

    private void copySelected() {
        Object uo = selectedObject();
        String text = nodeObjectText(uo);
        if (text == null) {
            if (feedback != null) {
                feedback.accept("⚠ 未选中可复制节点");
            }
            return;
        }
        copyToClipboard(text, "选中节点");
    }

    private void copySubtree() {
        Object uo = selectedObject();
        if (!(uo instanceof MethodNodeInfo)) {
            if (feedback != null) {
                feedback.accept("⚠ 仅方法节点可复制子树");
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendTreeText(sb, (MethodNodeInfo) uo, "", 0);
        copyToClipboard(sb.toString(), "子树");
    }

    private void copyAllTree() {
        Object rootObj = treeModel.getRoot() instanceof DefaultMutableTreeNode
                ? ((DefaultMutableTreeNode) treeModel.getRoot()).getUserObject() : null;
        if (!(rootObj instanceof MethodNodeInfo)) {
            if (feedback != null) {
                feedback.accept("⚠ 无整树可复制");
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendTreeText(sb, (MethodNodeInfo) rootObj, "", 0);
        copyToClipboard(sb.toString(), "全部");
    }

    /** 树节点 → 复制文本：方法=统计行；SQL=完整 SQL（不截断）+耗时；折叠组=全部被过滤方法明细 */
    private static String nodeObjectText(Object uo) {
        if (uo instanceof MethodNodeInfo) {
            return nodeText((MethodNodeInfo) uo) + "\n";
        }
        if (uo instanceof SqlRefInfo) {
            SqlRefInfo s = (SqlRefInfo) uo;
            return "SQL  " + (s.label == null ? "" : s.label)
                    + "  耗时" + fmt(s.nanos) + "ms" + (s.ok ? "" : " [异常]") + "\n";
        }
        if (uo instanceof FilteredGroup) {
            FilteredGroup g = (FilteredGroup) uo;
            StringBuilder sb = new StringBuilder("已过滤 " + g.methods.size() + " 个高频子调用 · 合计 "
                    + fmt(g.totalNanos) + "ms · 单次均值 " + fmtAvg(g.totalNanos / Math.max(1, g.calls)) + "\n");
            for (MethodNodeInfo m : g.methods) {
                sb.append("· ").append(m.name).append("  ×").append(m.calls)
                  .append("次  自身").append(fmt(m.selfNanos)).append("ms  总")
                  .append(fmt(m.totalNanos)).append("ms  单次最大").append(fmt(m.maxNanos)).append("ms\n");
            }
            return sb.toString();
        }
        return null;
    }

    /** 树 → 缩进文本：方法行 + 该方法下挂载的 SQL 行（与旧版缩进风格一致，去掉"方法"前缀） */
    private static void appendTreeText(StringBuilder sb, MethodNodeInfo m, String prefix, int depth) {
        if (m == null) {
            return;
        }
        sb.append(prefix).append(nodeText(m)).append('\n');
        if (m.sqls != null) {
            for (SqlRefInfo s : m.sqls) {
                sb.append(prefix).append("    ├─ ").append(sqlSummary(s)).append('\n');
            }
        }
        if (m.children == null || m.children.isEmpty()) {
            return;
        }
        int n = m.children.size();
        for (int i = 0; i < n; i++) {
            MethodNodeInfo c = m.children.get(i);
            boolean last = (i == n - 1);
            String childPrefix = prefix + (last ? "    └─ " : "    ├─ ");
            appendTreeText(sb, c, childPrefix, depth + 1);
        }
    }

    /** 写入系统剪贴板；成功/失败均通过 feedback 提示（成功摘要、失败原因），null feedback 时静默 */
    private void copyToClipboard(String text, String summary) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            if (feedback != null) {
                feedback.accept(summary);
            }
        } catch (Exception ex) {
            if (feedback != null) {
                feedback.accept("⚠ 复制失败: " + ex.getClass().getSimpleName());
            }
        }
    }

    // ==================== 公共工具 ====================

    /** 方法统计摘要：如 "方法 42个 ×199305次"（从调用树统计） */
    private static String methodSummary(RequestInfo r) {
        if (r.methodTree == null) {
            return "0个";
        }
        return treeCount(r.methodTree) + "个 ×" + treeCalls(r.methodTree) + "次";
    }

    private static int treeCount(MethodNodeInfo n) {
        int c = 1;
        if (n.children != null) {
            for (MethodNodeInfo x : n.children) {
                c += treeCount(x);
            }
        }
        return c;
    }

    private static long treeCalls(MethodNodeInfo n) {
        long c = n.calls;
        if (n.children != null) {
            for (MethodNodeInfo x : n.children) {
                c += treeCalls(x);
            }
        }
        return c;
    }

    private static void appendTreeFp(StringBuilder sb, MethodNodeInfo n, long noiseThresholdMs) {
        sb.append(n.name).append(':').append(n.calls).append(':')
                .append(n.selfNanos).append(':').append(n.totalNanos).append('{');
        if (n.sqls != null) {
            sb.append("sqls=").append(n.sqls.size()).append('|');
            for (SqlRefInfo s : n.sqls) {
                sb.append(s.label).append(':').append(s.nanos).append(':').append(s.ok).append(';');
            }
        }
        if (n.children != null) {
            long thresholdNs = noiseThresholdMs > 0 ? noiseThresholdMs * 1_000_000L : 0;
            long fgTotal = 0, fgCalls = 0;
            int fgCount = 0;
            for (MethodNodeInfo c : n.children) {
                if (thresholdNs > 0 && c.maxNanos < thresholdNs && !c.hasSql) {
                    fgCount++;
                    fgTotal += c.totalNanos;
                    fgCalls += c.calls;
                    continue;
                }
                appendTreeFp(sb, c, noiseThresholdMs);
            }
            if (fgCount > 0) {
                sb.append("filtered=").append(fgCount).append(':').append(fgCalls).append(':').append(fgTotal).append(';');
            }
        }
        sb.append('}');
    }

    /**
     * 全限定名（含参数签名）→ 简单名 "MenuServiceImpl.query"。
     * 必须先截掉签名部分再取最后两段，否则 lastIndexOf('.') 会取到参数类型包名里的点
     * （如 loadConfig(java.lang.String) 会被截成 "String)"）。
     */
    private static String simplify(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        int paren = name.indexOf('(');
        String head = paren >= 0 ? name.substring(0, paren) : name;
        int m = head.lastIndexOf('.');
        if (m < 0) {
            return head;
        }
        int c = head.lastIndexOf('.', m - 1);
        return c >= 0 ? head.substring(c + 1) : head;
    }

    static String fmt(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }

    /** 时间线行：SQL / HTTP 统一模型 */
    private static final class Row {
        final String kind;
        final String label;
        final long offset;
        final long nanos;
        final boolean ok;
        final String caller;

        Row(String kind, String label, long offset, long nanos, boolean ok, String caller) {
            this.kind = kind;
            this.label = label;
            this.offset = offset;
            this.nanos = nanos;
            this.ok = ok;
            this.caller = caller;
        }
    }
}
