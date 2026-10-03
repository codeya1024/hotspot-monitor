package com.codeya.hotspot.monitor.plugin.ui;

import com.codeya.hotspot.monitor.plugin.client.AgentClient;
import com.codeya.hotspot.monitor.plugin.client.RegistryReader;
import com.codeya.hotspot.monitor.plugin.client.RegistryReader.AgentEntry;
import com.codeya.hotspot.monitor.plugin.model.EndpointStat;
import com.codeya.hotspot.monitor.plugin.model.MethodNodeInfo;
import com.codeya.hotspot.monitor.plugin.model.MethodStat;
import com.codeya.hotspot.monitor.plugin.model.RequestInfo;
import com.codeya.hotspot.monitor.plugin.model.Snapshot;
import com.codeya.hotspot.monitor.plugin.model.SpanInfo;
import com.codeya.hotspot.monitor.plugin.model.SqlStat;
import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import javax.swing.JTable;
import com.intellij.ui.components.JBTabbedPane;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主面板：Agent 自动发现 + 请求列表 + 瀑布图 + 慢SQL + 端点统计。
 */
public class MainPanel extends JPanel implements Disposable {

    private final AgentClient client = new AgentClient();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hotspot-ui-fetch");
        t.setDaemon(true);
        return t;
    });
    private final Timer timer;
    private volatile int port = -1;
    private volatile String agentsKey = "";

    private final JComboBox<AgentEntry> agentCombo = new JComboBox<>();
    private final JTextField portField = new JTextField("28765", 6);
    private final JButton connectBtn = new JButton("连接");
    private final JButton refreshBtn = new JButton("刷新");
    private final JCheckBox autoBox = new JCheckBox("自动刷新", true);
    private final JBLabel statusLabel = new JBLabel("未连接");
    /** 复制反馈前一刻的状态栏文本，3 秒后恢复 */
    private String statusBeforeCopy = "未连接";
    private final javax.swing.Timer copiedResetTimer = new javax.swing.Timer(3000, e -> {
        statusLabel.setText(statusBeforeCopy);
        ((javax.swing.Timer) e.getSource()).stop();
    });

    /** 复制反馈（CopyableTable / WaterfallPanel 回调）：成功显示"✓ 已复制 …"，"⚠"开头为失败/未选中提示 */
    private void showCopied(String summary) {
        statusBeforeCopy = statusLabel.getText();
        statusLabel.setText(summary != null && summary.startsWith("⚠") ? summary : "✓ 已复制: " + summary);
        copiedResetTimer.restart();
    }

    private final List<RequestInfo> currentRequests = new ArrayList<>();
    /** 请求列表指纹：id 序列未变化时不重建请求表（保留选中与滚动位置，避免每秒闪烁） */
    private String requestsKey = "";
    private final DefaultTableModel requestsModel = new DefaultTableModel(
            new Object[]{"时间", "方法", "路径", "状态", "总耗时ms", "SQL数", "SQL耗时ms", "HTTP数", "HTTP耗时ms"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 0: return Long.class;                            // 时间戳
                case 4: case 6: case 8: return Double.class;          // 总耗时 / SQL耗时 / HTTP耗时
                case 5: case 7: return Integer.class;                  // SQL数 / HTTP数
                default: return String.class;                          // 方法 / 路径 / 状态
            }
        }
    };
    private final JTable requestsTable = new CopyableTable(requestsModel, false, this::showCopied);
    private final WaterfallPanel waterfall = new WaterfallPanel();
    {
        waterfall.setFeedback(this::showCopied);
    }

    private final DefaultTableModel sqlModel = editableOff(
            new Object[]{"SQL", "调用链", "次数", "总耗时ms", "最大ms", "最近时间"},
            new Class[]{String.class, String.class, Integer.class, Double.class, Double.class, Long.class});
    private final JTable sqlTable = new CopyableTable(sqlModel, true, this::showCopied); // 单元格选择：单击选中单格，⌘C 复制该格
    private final DefaultTableModel httpModel = editableOff(
            new Object[]{"下游调用", "次数", "总耗时ms", "最大ms", "最近时间"},
            new Class[]{String.class, Integer.class, Double.class, Double.class, Long.class});
    private final JTable httpTable = new CopyableTable(httpModel, true, this::showCopied); // 单元格选择：单击选中单格，⌘C 复制该格
    private final DefaultTableModel epModel = editableOff(
            new Object[]{"端点", "次数", "平均ms", "P50ms", "P95ms", "最大ms"},
            new Class[]{String.class, Integer.class, Double.class, Double.class, Double.class, Double.class});
    private final JTable epTable = new CopyableTable(epModel, true, this::showCopied); // 单元格选择：单击选中单格，⌘C 复制该格
    private final DefaultTableModel orphanModel = editableOff(
            new Object[]{"类型", "内容", "调用来源", "耗时ms", "发生时间"},
            new Class[]{String.class, String.class, String.class, Double.class, String.class});
    private final JTable orphanTable = new CopyableTable(orphanModel, true, this::showCopied); // 单元格选择：单击选中单格，⌘C 复制该格
    private final DefaultTableModel methodModel = editableOff(
            new Object[]{"方法", "次数", "自身ms", "总ms", "最大ms"},
            new Class[]{String.class, Integer.class, Double.class, Double.class, Double.class});
    private final JTable methodTable = new CopyableTable(methodModel, true, this::showCopied); // 单元格选择：单击选中单格，⌘C 复制该格

    public MainPanel() {
        super(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.30);
        requestsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestsTable.setAutoCreateRowSorter(true);
        JScrollPane reqScroll = new JScrollPane(requestsTable);
        reqScroll.setBorder(BorderFactory.createEmptyBorder()); // 去掉表头正上方的边框线，避免像被截断的文本行
        reqScroll.setPreferredSize(new Dimension(420, 170));
        split.setTopComponent(reqScroll);
        split.setBottomComponent(buildDetailTabs());
        add(split, BorderLayout.CENTER);

        requestsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int viewRow = requestsTable.getSelectedRow();
            if (viewRow >= 0) {
                int modelRow = requestsTable.convertRowIndexToModel(viewRow);
                showRequestDetail(modelRow);
            }
        });

        // 下拉框切换 agent 时，端口输入框同步显示该 agent 的端口
        agentCombo.addActionListener(e -> {
            Object sel = agentCombo.getSelectedItem();
            if (sel instanceof AgentEntry) {
                portField.setText(String.valueOf(((AgentEntry) sel).port));
            }
        });
        connectBtn.addActionListener(e -> connect(resolvePort()));
        refreshBtn.addActionListener(e -> {
            refreshAgentsQuiet();
            maybeFetch();
        });
        timer = new Timer(1000, e -> {
            maybeFetch();
            refreshAgentsQuiet();
        });
        autoBox.addActionListener(e -> {
            if (autoBox.isSelected()) {
                timer.start();
            } else {
                timer.stop();
            }
        });

        refreshAgents();
        bindRenderers();
        timer.start();
    }

    /** 耗时列显示为数值（排序按数字、显示 %.2f）；时间列显示为 HH:mm:ss.SSS */
    private static final DefaultTableCellRenderer MS_RENDERER = new DefaultTableCellRenderer() {
        @Override
        protected void setValue(Object v) {
            if (v instanceof Number) {
                setText(String.format("%.2f", ((Number) v).doubleValue()));
            } else {
                setText(v == null ? "" : String.valueOf(v));
            }
        }
    };
    private static final DefaultTableCellRenderer TIME_RENDERER = new DefaultTableCellRenderer() {
        @Override
        protected void setValue(Object v) {
            if (v instanceof Number) {
                setText(((Number) v).longValue() <= 0 ? "-" : time(((Number) v).longValue()));
            } else {
                setText(v == null ? "" : String.valueOf(v));
            }
        }
    };

    private void bindRenderers() {
        JTable[] tables = {requestsTable, sqlTable, httpTable, epTable, orphanTable, methodTable};
        for (JTable t : tables) {
            // 等宽字体：固定位数的数字/时间戳（耗时ms、发生时间 HH:mm:ss.SSS 等）逐列对齐
            t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            t.setAutoCreateRowSorter(true);
            TableRowSorter<? extends TableModel> sorter = (TableRowSorter<? extends TableModel>) t.getRowSorter();
            for (int i = 0; i < t.getColumnCount(); i++) {
                String n = t.getColumnName(i);
                Class<?> cls = t.getModel().getColumnClass(i);
                // TableRowSorter 对 Double/Long 等非内置类型会回退到字符串排序，
                // 这里对数值列显式注册自然序（数字）比较器
                if (Number.class.isAssignableFrom(cls)) {
                    sorter.setComparator(i, Comparator.naturalOrder());
                }
                if (n.endsWith("ms")) {
                    t.getColumnModel().getColumn(i).setCellRenderer(MS_RENDERER);
                } else if ("时间".equals(n) || "最近时间".equals(n)) {
                    t.getColumnModel().getColumn(i).setCellRenderer(TIME_RENDERER);
                }
            }
        }
    }

    private JComponent buildToolbar() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        top.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        agentCombo.setPreferredSize(new Dimension(260, 26));
        top.add(new JBLabel("Agent:"));
        top.add(agentCombo);
        top.add(new JBLabel("端口:"));
        portField.setPreferredSize(new Dimension(60, 26));
        top.add(portField);
        top.add(connectBtn);
        top.add(autoBox);
        top.add(refreshBtn);
        JButton clearBtn = new JButton("清空");
        clearBtn.setToolTipText("清空当前 agent 的全部监控数据，重新记录");
        clearBtn.addActionListener(e -> {
            if (port < 0) {
                statusLabel.setText("先连接 agent 再清空");
                return;
            }
            pool.execute(() -> {
                boolean ok = client.clear(port);
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText(ok ? "已清空，重新记录中..." : "清空失败（agent 未响应）"));
            });
        });
        top.add(clearBtn);
        top.add(statusLabel);
        return top;
    }

    private JComponent buildDetailTabs() {
        JBTabbedPane tabs = new JBTabbedPane();

        // 瀑布图本体即可选中复制；WaterfallPanel 内部 JSplitPane 上下分栏（时间线/方法树各自滚动条），占满 tab
        tabs.addTab("瀑布图", waterfall);
        tabs.addTab("方法", new JScrollPane(methodTable));
        tabs.addTab("慢SQL", new JScrollPane(sqlTable));
        tabs.addTab("慢下游HTTP", new JScrollPane(httpTable));
        tabs.addTab("端点统计", new JScrollPane(epTable));
        tabs.addTab("无主SQL/HTTP", new JScrollPane(orphanTable));
        return tabs;
    }

    private void refreshAgents() {
        agentCombo.removeAllItems();
        List<AgentEntry> agents = RegistryReader.list();
        if (agents.isEmpty()) {
            agentCombo.addItem(null);
        } else {
            for (AgentEntry a : agents) {
                agentCombo.addItem(a);
            }
            AgentEntry first = agents.get(0);
            portField.setText(String.valueOf(first.port));
        }
    }

    /** 集合变化时才重建下拉框（避免每秒打断用户选择） */
    private void refreshAgentsQuiet() {
        StringBuilder key = new StringBuilder();
        List<AgentEntry> agents = RegistryReader.list();
        for (AgentEntry a : agents) {
            key.append(a.pid).append(':').append(a.port).append(',');
        }
        if (!key.toString().equals(agentsKey)) {
            agentsKey = key.toString();
            refreshAgents();
        }
    }

    private int resolvePort() {
        Object sel = agentCombo.getSelectedItem();
        if (sel instanceof AgentEntry) {
            return ((AgentEntry) sel).port;
        }
        try {
            return Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            return 28765;
        }
    }

    private void connect(int targetPort) {
        port = -1;
        statusLabel.setText("连接中 " + targetPort + " ...");
        pool.execute(() -> {
            boolean ok = client.health(targetPort);
            SwingUtilities.invokeLater(() -> {
                if (ok) {
                    port = targetPort;
                    statusLabel.setText("已连接 127.0.0.1:" + targetPort);
                    maybeFetch();
                } else {
                    statusLabel.setText("无法连接端口 " + targetPort + "（agent 未启动？）");
                }
            });
        });
    }

    private void maybeFetch() {
        if (port < 0) {
            return;
        }
        pool.execute(() -> {
            try {
                Snapshot snap = client.fetchSnapshot(port);
                SwingUtilities.invokeLater(() -> apply(snap));
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("连接中断: " + t.getMessage()));
            }
        });
    }

    /** epoch ms → "HH:mm:ss.SSS"（当天发生时间） */
    private static String fmtClock(long atMs) {
        if (atMs <= 0) {
            return "";
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
        f.setTimeZone(java.util.TimeZone.getDefault());
        return f.format(new java.util.Date(atMs));
    }

    private void apply(Snapshot snap) {
        if (snap == null) {
            return;
        }
        if (snap.agent != null) {
            String app = snap.agent.app == null ? "" : snap.agent.app;
            int n = snap.requests == null ? 0 : snap.requests.size();
            statusLabel.setText("已连接 pid " + snap.agent.pid + " · " + n + " 条请求");
            statusLabel.setToolTipText(app + " (pid " + snap.agent.pid + " · 端口 " + port + ")");
        }
        // 请求表：id 序列变化才重建（保留选中/滚动，避免每秒闪烁）
        StringBuilder kb = new StringBuilder();
        if (snap.requests != null) {
            for (RequestInfo r : snap.requests) {
                kb.append(r.id).append(',');
            }
        }
        String key = kb.toString();
        boolean listChanged = !key.equals(requestsKey);
        requestsKey = key;
        if (listChanged) {
            long selId = -1;
            int selView = requestsTable.getSelectedRow();
            if (selView >= 0 && selView < currentRequests.size()) {
                selId = currentRequests.get(requestsTable.convertRowIndexToModel(selView)).id;
            }
            requestsModel.setRowCount(0);
            currentRequests.clear();
            if (snap.requests != null) {
                for (RequestInfo r : snap.requests) {
                    currentRequests.add(r);
                    requestsModel.addRow(new Object[]{
                            r.startAtMs, r.method, r.path, r.status == 0 ? "-" : String.valueOf(r.status),
                            r.totalNanos / 1_000_000.0, r.sqlCount, r.sqlNanos / 1_000_000.0,
                            r.httpCount, r.httpNanos / 1_000_000.0
                    });
                }
            }
            // 列表变化后按 id 恢复之前的选中
            if (selId >= 0) {
                for (int i = 0; i < currentRequests.size(); i++) {
                    if (currentRequests.get(i).id == selId) {
                        requestsTable.setRowSelectionInterval(requestsTable.convertRowIndexToView(i), requestsTable.convertRowIndexToView(i));
                        break;
                    }
                }
            }
        }
        // 方法树噪音折叠阈值（agent 输出全量树，插件按该阈值折叠高频小调用）
        if (snap.thresholds != null) {
            waterfall.setNoiseThresholdMs(snap.thresholds.noiseThresholdMs);
        }
        // 慢 SQL（含最近调用来源）——重建前记录选中，重建后按 SQL 文本恢复（每秒刷新不丢选中）
        fillAgg(sqlTable, sqlModel, snap.slowSqls, true);
        // 慢下游 HTTP
        fillAgg(httpTable, httpModel, snap.slowHttps, false);
        // 端点统计（端点名唯一，按第一列恢复选中）
        SelKey epSel = firstColKey(epTable);
        epModel.setRowCount(0);
        if (snap.endpoints != null) {
            for (EndpointStat e : snap.endpoints) {
                epModel.addRow(new Object[]{
                        e.name, e.count,
                        e.avgNanos / 1_000_000.0, e.p50Nanos / 1_000_000.0,
                        e.p95Nanos / 1_000_000.0, e.maxNanos / 1_000_000.0
                });
            }
        }
        restoreFirstCol(epTable, epSel);
        // 无主SQL/HTTP：不属于任何请求的孤儿 span（异步/定时/后台线程），带调用来源
        // 行内容（label/caller/耗时/时间）固定不变，按整行指纹恢复选中
        SelKey orphanSel = fullRowKey(orphanTable);
        orphanModel.setRowCount(0);
        if (snap.orphanSpans != null) {
            for (SpanInfo s : snap.orphanSpans) {
                orphanModel.addRow(new Object[]{"sql".equals(s.kind) ? "SQL" : "HTTP", s.label,
                        s.caller == null || s.caller.isEmpty() ? "(未知线程)" : s.caller,
                        s.nanos / 1_000_000.0, fmtClock(s.atMs)});
            }
        }
        restoreRow(orphanTable, orphanSel);
        // 保持当前选中请求的瀑布/方法详情跟随最新数据刷新
        int selView = requestsTable.getSelectedRow();
        if (selView >= 0) {
            showRequestDetail(requestsTable.convertRowIndexToModel(selView));
        }
    }

    /** 展示选中请求的瀑布图 + 方法耗时（自身耗时倒序） */
    private void showRequestDetail(int modelRow) {
        if (modelRow < 0 || modelRow >= currentRequests.size()) {
            waterfall.setRequest(null);
            methodModel.setRowCount(0);
            return;
        }
        RequestInfo r = currentRequests.get(modelRow);
        waterfall.setRequest(r);
        SelKey methodSel = firstColKey(methodTable);
        methodModel.setRowCount(0);
        // agent 已不再输出 methods 聚合，从 methodTree 递归聚合出方法统计（方法/次数/自身/总/最大）
        List<MethodStat> ms = new ArrayList<MethodStat>();
        if (r.methodTree != null) {
            collectMethodStats(r.methodTree, ms);
        }
        ms.sort(Comparator.comparingLong((MethodStat m) -> -m.selfNanos));
        for (MethodStat m : ms) {
            methodModel.addRow(new Object[]{
                    m.name, m.calls,
                    m.selfNanos / 1_000_000.0, m.totalNanos / 1_000_000.0,
                    m.maxNanos / 1_000_000.0
            });
        }
        restoreFirstCol(methodTable, methodSel);
    }

    // ==================== 选中保持：表格每秒重建后恢复选中行（含列，cellSelection 表格不丢列） ====================

    /** 选中标识：重建前记录（第一列文本/整行指纹 + 选中的列），重建后按标识恢复行与列 */
    private static final class SelKey {
        final String key;
        final int col;   // view 列索引；-1 = 未选中列（行选择模式）
        SelKey(String key, int col) {
            this.key = key;
            this.col = col;
        }
    }

    /** 当前选中行第一列的文本 + 选中列（稳定标识；未选中返回 null） */
    private static SelKey firstColKey(JTable t) {
        int v = t.getSelectedRow();
        if (v < 0) {
            return null;
        }
        Object o = t.getModel().getValueAt(t.convertRowIndexToModel(v), 0);
        return new SelKey(o == null ? null : String.valueOf(o), t.getSelectedColumn());
    }

    /** 重建后按第一列文本恢复选中（只匹配一行，优先最早出现），行与列一并恢复 */
    private static void restoreFirstCol(JTable t, SelKey k) {
        if (k == null || k.key == null) {
            return;
        }
        for (int i = 0; i < t.getRowCount(); i++) {
            Object o = t.getModel().getValueAt(i, 0);
            if (o != null && k.key.equals(String.valueOf(o))) {
                selectRowCol(t, i, k.col);
                break;
            }
        }
    }

    /** 当前选中行整行指纹 + 选中列（无主 tab 用：label/caller/耗时/时间均固定，唯一且稳定） */
    private static SelKey fullRowKey(JTable t) {
        int v = t.getSelectedRow();
        if (v < 0) {
            return null;
        }
        return new SelKey(rowKey(t, t.convertRowIndexToModel(v)), t.getSelectedColumn());
    }

    private static String rowKey(JTable t, int modelRow) {
        StringBuilder sb = new StringBuilder(64);
        for (int c = 0; c < t.getColumnCount(); c++) {
            if (c > 0) {
                sb.append('|');
            }
            Object o = t.getModel().getValueAt(modelRow, c);
            sb.append(o == null ? "" : o);
        }
        return sb.toString();
    }

    /** 重建后按整行指纹恢复选中，行与列一并恢复 */
    private static void restoreRow(JTable t, SelKey k) {
        if (k == null || k.key == null) {
            return;
        }
        for (int i = 0; i < t.getRowCount(); i++) {
            if (rowKey(t, i).equals(k.key)) {
                selectRowCol(t, i, k.col);
                break;
            }
        }
    }

    /** 恢复行选中；表格允许列选择（cellSelection）且原列有效时，一并恢复列选中 */
    private static void selectRowCol(JTable t, int modelRow, int viewCol) {
        int vr = t.convertRowIndexToView(modelRow);
        t.setRowSelectionInterval(vr, vr);
        if (t.getColumnSelectionAllowed() && viewCol >= 0 && viewCol < t.getColumnCount()) {
            t.setColumnSelectionInterval(viewCol, viewCol);
        }
    }

    /**
     * 递归遍历 methodTree，收集方法统计，**按方法全限定名合并**。
     * 方法树里同一个方法名（如递归方法 setChild）在不同调用层级下是不同节点
     * （父方法不同），若逐节点输出会看到同名方法多行、误以为重载。
     * 这里按 name 聚合：次数/自身/总耗时累加，最大耗时取各节点峰值。
     */
    private static void collectMethodStats(MethodNodeInfo n, List<MethodStat> out) {
        Map<String, MethodStat> byName = new LinkedHashMap<>();
        collectMethodStats(n, byName);
        out.addAll(byName.values());
    }

    private static void collectMethodStats(MethodNodeInfo n, Map<String, MethodStat> byName) {
        MethodStat ms = byName.get(n.name);
        if (ms == null) {
            ms = new MethodStat();
            ms.name = n.name;
            byName.put(n.name, ms);
        }
        ms.calls += n.calls;
        ms.selfNanos += n.selfNanos;
        ms.totalNanos += n.totalNanos;
        if (n.maxNanos > ms.maxNanos) {
            ms.maxNanos = n.maxNanos;
        }
        if (n.children != null) {
            for (MethodNodeInfo c : n.children) {
                collectMethodStats(c, byName);
            }
        }
    }

    private void fillAgg(JTable table, DefaultTableModel model, List<SqlStat> list, boolean sqlTab) {
        // 重建前记录选中行（聚合表以第一列 label 为稳定标识：SQL 文本/下游调用），重建后恢复
        SelKey selKey = firstColKey(table);
        model.setRowCount(0);
        if (list == null) {
            return;
        }
        for (SqlStat s : list) {
            if (sqlTab) {
                model.addRow(new Object[]{
                        s.label, s.lastCaller == null ? "-" : s.lastCaller,
                        s.count, s.totalNanos / 1_000_000.0,
                        s.maxNanos / 1_000_000.0, s.lastAtMs
                });
            } else {
                model.addRow(new Object[]{
                        s.label, s.count, s.totalNanos / 1_000_000.0,
                        s.maxNanos / 1_000_000.0, s.lastAtMs
                });
            }
        }
        restoreFirstCol(table, selKey);
    }

    private static DefaultTableModel editableOff(Object[] cols, Class<?>[] types) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return types[columnIndex];
            }
        };
    }

    private static String time(long ms) {
        return ms <= 0 ? "-" : new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(ms));
    }

    @Override
    public void dispose() {
        timer.stop();
        pool.shutdownNow();
    }
}
