package jeeanalyzer.gui;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import jeeanalyzer.Analyzer;
import jeeanalyzer.PathCsv;
import jeeanalyzer.Model.Facelet;
import jeeanalyzer.Model.PathRow;

/** メインウィンドウ: プロジェクト登録 / xhtml 選択 / 解析結果 / ソース表示 */
final class AnalyzerFrame extends JFrame {
    private final ProjectStore store = new ProjectStore();
    private final DefaultListModel<RegisteredProject> projectModel = new DefaultListModel<>();
    private final JList<RegisteredProject> projectList = new JList<>(projectModel);
    private final DefaultListModel<XhtmlItem> xhtmlModel = new DefaultListModel<>();
    private final JList<XhtmlItem> xhtmlList = new JList<>(xhtmlModel);
    private final PathsTableModel pathsModel = new PathsTableModel();
    private final PathTreePanel treePanel = new PathTreePanel();
    private final JTextField searchField = new JTextField();
    private final JCheckBox searchXhtml = new JCheckBox("xhtml", true);
    private final JCheckBox searchButton = new JCheckBox("button", true);
    private final JCheckBox searchBean = new JCheckBox("bean", true);
    private final JCheckBox searchService = new JCheckBox("service", true);
    private final JCheckBox searchDao = new JCheckBox("dao", true);
    private final JCheckBox searchSqlTable = new JCheckBox("sql/table", true);
    private final JLabel status = new JLabel("プロジェクトを登録して解析してください");
    private final SourcePane sourcePane = new SourcePane();
    private final JButton analyzeBtn = new JButton("解析実行");
    private final JCheckBox allXhtml = new JCheckBox("全画面を解析", true);
    private final JCheckBox mergeResults = new JCheckBox("既存結果とマージ", true);
    private final JTextField outField = new JTextField();
    private final JToggleButton toggleLeftBtn = new JToggleButton("プロジェクトパネル非表示", true);

    private JComponent leftPanel;
    private JSplitPane outerSplit;
    private JSplitPane innerSplit;
    private int savedLeftDivider = -1;
    private boolean applyingRatio;

    AnalyzerFrame() {
        super("Jakarta EE Analyzer");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 700));
        setSize(1440, 820);
        setLocationRelativeTo(null);

        for (RegisteredProject p : store.load()) projectModel.addElement(p);

        projectList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        projectList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof RegisteredProject rp) {
                    setText(rp.displayName() + "  —  " + rp.primary);
                    setToolTipText(rp.tooltip());
                }
                return this;
            }
        });
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadXhtmlForSelection();
        });

        xhtmlList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        xhtmlList.setEnabled(!allXhtml.isSelected());
        allXhtml.addActionListener(e -> {
            xhtmlList.setEnabled(!allXhtml.isSelected());
            if (allXhtml.isSelected()) mergeResults.setSelected(false);
            else mergeResults.setSelected(true);
        });
        mergeResults.setToolTipText("部分解析時、未選択画面の paths.csv / 画面レポートを残して今回分だけ差し替えます");

        treePanel.setOnSelect(this::onTreeSelect);
        pathsModel.addTableModelListener(e -> refreshTrees());

        searchField.putClientProperty("JTextField.placeholderText", "検索語");
        DocumentListener searchListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        };
        searchField.getDocument().addDocumentListener(searchListener);
        for (JCheckBox cb : List.of(searchXhtml, searchButton, searchBean, searchService, searchDao, searchSqlTable)) {
            cb.addActionListener(e -> filter());
        }

        toggleLeftBtn.setToolTipText("左のプロジェクト／xhtml パネルを表示／非表示");
        toggleLeftBtn.addActionListener(e -> setLeftPanelVisible(toggleLeftBtn.isSelected()));

        Path lastOut = store.loadLastOut();
        if (lastOut != null) {
            outField.setText(lastOut.toString());
        } else {
            outField.setText(Path.of("analysis-output").toAbsolutePath().toString());
        }

        setContentPane(buildUi());
        if (!projectModel.isEmpty()) projectList.setSelectedIndex(0);
        SwingUtilities.invokeLater(() -> {
            applyPanelRatio();
            loadExistingResults();
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (toggleLeftBtn.isSelected()) applyPanelRatio();
            }
        });
    }

    /** 前回出力の paths.csv を読み込み、起動直後にツリー表示 */
    private void loadExistingResults() {
        Path out = Path.of(outField.getText().trim()).toAbsolutePath().normalize();
        List<RegisteredProject> projects = allProjects();

        List<PathRowView> views = new ArrayList<>();
        Set<Path> loadedCsv = new HashSet<>();

        // 複数プロジェクト実行時: out/<projectName>/paths.csv
        for (RegisteredProject project : projects) {
            Path csv = out.resolve(safeName(project.primary)).resolve("paths.csv");
            if (Files.isRegularFile(csv)) {
                views.addAll(readViews(project, csv));
                loadedCsv.add(csv.toAbsolutePath().normalize());
            }
        }

        // 単一出力: out/paths.csv
        Path rootCsv = out.resolve("paths.csv");
        if (Files.isRegularFile(rootCsv) && loadedCsv.add(rootCsv.toAbsolutePath().normalize())) {
            RegisteredProject project = resolveProjectForCsv(projects, rootCsv);
            if (project != null) views.addAll(readViews(project, rootCsv));
        }

        // フォールバック: 登録プロジェクト直下や sample-output
        if (views.isEmpty()) {
            for (RegisteredProject project : projects) {
                for (Path cand : List.of(
                        project.primary.resolve("analysis-output").resolve("paths.csv"),
                        Path.of("sample-output").toAbsolutePath().normalize().resolve("paths.csv"),
                        Path.of("analysis-output").toAbsolutePath().normalize().resolve("paths.csv"))) {
                    if (!Files.isRegularFile(cand) || !loadedCsv.add(cand.toAbsolutePath().normalize())) continue;
                    RegisteredProject proj = resolveProjectForCsv(projects, cand);
                    if (proj == null) proj = project;
                    views.addAll(readViews(proj, cand));
                    outField.setText(cand.getParent().toString());
                    break;
                }
                if (!views.isEmpty()) break;
            }
        }

        if (views.isEmpty()) {
            status.setText("前回の解析結果なし（解析を実行してください）");
            return;
        }
        pathsModel.setRows(views);
        applySearch();
        refreshTrees();
        status.setText("前回結果を読込: " + views.size() + " パス / " + outField.getText());
    }

    private List<PathRowView> readViews(RegisteredProject project, Path csv) {
        try {
            List<PathRowView> list = new ArrayList<>();
            for (PathRow row : PathCsv.read(csv)) list.add(new PathRowView(project, row));
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** definedIn のパス断片からプロジェクトを推定。だめなら先頭プロジェクト */
    private RegisteredProject resolveProjectForCsv(List<RegisteredProject> projects, Path csv) {
        if (projects.isEmpty()) {
            try {
                List<PathRow> rows = PathCsv.read(csv);
                for (PathRow r : rows) {
                    if (r.definedIn == null) continue;
                    String def = r.definedIn.replace('\\', '/');
                    int slash = def.indexOf('/');
                    if (slash > 0) {
                        Path guess = Path.of(def.substring(0, slash)).toAbsolutePath().normalize();
                        if (Files.isDirectory(guess)) return new RegisteredProject(guess);
                    }
                }
            } catch (Exception ignore) { }
            return new RegisteredProject(Path.of(".").toAbsolutePath().normalize());
        }
        if (projects.size() == 1) return projects.get(0);
        try {
            List<PathRow> rows = PathCsv.read(csv);
            for (RegisteredProject project : projects) {
                String name = project.primary.getFileName() != null ? project.primary.getFileName().toString() : "";
                for (PathRow r : rows) {
                    String def = r.definedIn == null ? "" : r.definedIn.replace('\\', '/');
                    if (def.contains("/" + name + "/") || def.startsWith(name + "/")) return project;
                }
            }
        } catch (Exception ignore) { }
        return projects.get(0);
    }

    private List<RegisteredProject> allProjects() {
        List<RegisteredProject> all = new ArrayList<>();
        for (int i = 0; i < projectModel.size(); i++) all.add(projectModel.get(i));
        return all;
    }

    private JPanel buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 8, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(toggleLeftBtn);
        root.add(top, BorderLayout.NORTH);

        leftPanel = buildLeft();
        JComponent center = buildCenter();
        outerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, center);
        outerSplit.setContinuousLayout(true);
        outerSplit.setResizeWeight(1.0 / 6.0); // 1 : (2+3)
        outerSplit.setOneTouchExpandable(true);

        root.add(outerSplit, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        return root;
    }

    /** プロジェクト : 呼び出しパス : ソース = 1 : 2 : 3 */
    private void applyPanelRatio() {
        if (outerSplit == null || innerSplit == null || applyingRatio) return;
        if (!toggleLeftBtn.isSelected()) return;
        int w = outerSplit.getWidth();
        if (w <= 0) return;
        applyingRatio = true;
        try {
            int left = Math.max(140, w / 6);           // 1/6
            outerSplit.setDividerLocation(left);
            int rest = Math.max(1, w - left - outerSplit.getDividerSize());
            int tree = Math.max(160, rest * 2 / 5);    // 残り5のうち2 = 全体の2/6
            innerSplit.setDividerLocation(tree);
        } finally {
            applyingRatio = false;
        }
    }

    private void setLeftPanelVisible(boolean visible) {
        if (outerSplit == null || leftPanel == null) return;
        if (visible) {
            outerSplit.setLeftComponent(leftPanel);
            outerSplit.setDividerSize(UIManager.getInt("SplitPane.dividerSize"));
            if (outerSplit.getDividerSize() <= 0) outerSplit.setDividerSize(6);
            SwingUtilities.invokeLater(() -> {
                if (savedLeftDivider > 0) outerSplit.setDividerLocation(savedLeftDivider);
                else applyPanelRatio();
            });
            toggleLeftBtn.setText("プロジェクトパネル非表示");
        } else {
            savedLeftDivider = outerSplit.getDividerLocation();
            JPanel empty = new JPanel();
            empty.setMinimumSize(new Dimension(0, 0));
            empty.setPreferredSize(new Dimension(0, 0));
            outerSplit.setLeftComponent(empty);
            outerSplit.setDividerLocation(0);
            outerSplit.setDividerSize(0);
            toggleLeftBtn.setText("プロジェクトパネルを表示");
            // 左非表示時は パス:ソース = 2:3
            SwingUtilities.invokeLater(() -> {
                int w = outerSplit.getWidth();
                if (w > 0 && innerSplit != null) innerSplit.setDividerLocation(Math.max(160, w * 2 / 5));
            });
        }
    }

    private JComponent buildLeft() {
        JPanel projects = new JPanel(new BorderLayout(4, 4));
        projects.setBorder(BorderFactory.createTitledBorder("解析対象プロジェクト（複数登録可）"));
        projects.add(new JScrollPane(projectList), BorderLayout.CENTER);
        JPanel projBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton add = new JButton("追加…");
        JButton addExtra = new JButton("関連フォルダ…");
        JButton remove = new JButton("削除");
        add.setToolTipText("主フォルダ（XHTML / Bean など）を登録");
        addExtra.setToolTipText("選択中プロジェクトに DAO / SQL など別フォルダを追加");
        add.addActionListener(e -> addProject());
        addExtra.addActionListener(e -> addRelatedFolder());
        remove.addActionListener(e -> removeProjects());
        projBtns.add(add);
        projBtns.add(addExtra);
        projBtns.add(remove);
        projects.add(projBtns, BorderLayout.SOUTH);

        JPanel xhtml = new JPanel(new BorderLayout(4, 4));
        xhtml.setBorder(BorderFactory.createTitledBorder("解析する xhtml（複数選択可）"));
        JPanel xhtmlTop = new JPanel(new BorderLayout());
        JPanel xhtmlOpts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        xhtmlOpts.add(allXhtml);
        xhtmlOpts.add(mergeResults);
        xhtmlTop.add(xhtmlOpts, BorderLayout.CENTER);
        JButton reload = new JButton("再読込");
        reload.addActionListener(e -> loadXhtmlForSelection());
        xhtmlTop.add(reload, BorderLayout.EAST);
        xhtml.add(xhtmlTop, BorderLayout.NORTH);
        xhtml.add(new JScrollPane(xhtmlList), BorderLayout.CENTER);

        JPanel out = new JPanel(new BorderLayout(4, 4));
        out.setBorder(BorderFactory.createTitledBorder("出力先"));
        out.add(outField, BorderLayout.CENTER);
        JButton browseOut = new JButton("…");
        browseOut.addActionListener(e -> browseOutDir());
        out.add(browseOut, BorderLayout.EAST);

        analyzeBtn.addActionListener(e -> runAnalysis());
        JPanel run = new JPanel(new BorderLayout());
        run.add(analyzeBtn, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, projects, xhtml);
        split.setResizeWeight(0.4);
        split.setContinuousLayout(true);

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, 6));
        bottom.add(out, BorderLayout.CENTER);
        bottom.add(run, BorderLayout.SOUTH);
        left.add(bottom, BorderLayout.SOUTH);
        left.setMinimumSize(new Dimension(160, 200));
        return left;
    }

    private JComponent buildCenter() {
        JPanel fieldBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fieldBar.add(new JLabel("検索対象:"));
        fieldBar.add(searchXhtml);
        fieldBar.add(searchButton);
        fieldBar.add(searchBean);
        fieldBar.add(searchService);
        fieldBar.add(searchDao);
        fieldBar.add(searchSqlTable);
        JButton allOn = new JButton("全てON");
        JButton allOff = new JButton("全てOFF");
        allOn.setToolTipText("検索対象をすべて選択");
        allOff.setToolTipText("検索対象をすべて解除");
        allOn.addActionListener(e -> setSearchTargets(true));
        allOff.addActionListener(e -> setSearchTargets(false));
        fieldBar.add(allOn);
        fieldBar.add(allOff);

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.add(new JLabel("検索"), BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(treePanel.countLabel(), BorderLayout.EAST);

        JPanel searchBar = new JPanel(new BorderLayout(0, 4));
        searchBar.add(fieldBar, BorderLayout.NORTH);
        searchBar.add(searchRow, BorderLayout.SOUTH);

        JPanel treeWrap = new JPanel(new BorderLayout(4, 4));
        treeWrap.setBorder(BorderFactory.createTitledBorder("呼び出しパス（ツリー）"));
        treeWrap.add(searchBar, BorderLayout.NORTH);
        treeWrap.add(treePanel, BorderLayout.CENTER);
        treeWrap.setMinimumSize(new Dimension(160, 200));

        JPanel sourceWrap = new JPanel(new BorderLayout(4, 4));
        sourceWrap.setBorder(BorderFactory.createTitledBorder("ソース"));
        sourceWrap.add(sourcePane, BorderLayout.CENTER);
        sourceWrap.setMinimumSize(new Dimension(200, 200));
        sourcePane.clear();

        innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeWrap, sourceWrap);
        innerSplit.setContinuousLayout(true);
        innerSplit.setResizeWeight(2.0 / 5.0); // パス:ソース = 2:3（全体 1:2:3 の残り）
        return innerSplit;
    }

    private void refreshTrees() {
        treePanel.setRows(pathsModel.filteredSnapshot(), pathsModel.query(), pathsModel.searchFields());
    }

    private void onTreeSelect(PathTreeNode node) {
        if (node == null || node.view == null) {
            sourcePane.clear();
            return;
        }
        showSourceForNode(node);
    }

    /** ツリーで選んだノード種別に応じたソースを表示 */
    private void showSourceForNode(PathTreeNode node) {
        PathRowView v = node.view;
        PathRow r = v.row;
        switch (node.kind) {
            case XHTML, BUTTON -> sourcePane.showFile(v.roots, r.definedIn, r.line);
            case BEAN -> showBeanSource(v);
            case SERVICE -> showServiceSource(v, node.hopIndex);
            case DAO -> showDaoSource(v);
            case SQL, TABLE, CRUD -> {
                if (r.sqlSource != null && !r.sqlSource.isBlank()) {
                    sourcePane.showFile(v.roots, r.sqlSource, SourcePane.parseLineFromSource(r.sqlSource));
                } else {
                    showDaoSource(v);
                }
            }
            case GROUP, ROOT -> {
                if (node.sourceHint != null) showByHint(v, node.sourceHint, node.hopIndex);
                else sourcePane.showFile(v.roots, r.definedIn, r.line);
            }
        }
    }

    private void showByHint(PathRowView v, String hint, int hopIndex) {
        switch (hint) {
            case "sql" -> {
                PathRow r = v.row;
                if (r.sqlSource != null && !r.sqlSource.isBlank()) {
                    sourcePane.showFile(v.roots, r.sqlSource, SourcePane.parseLineFromSource(r.sqlSource));
                } else {
                    sourcePane.showFile(v.roots, r.definedIn, r.line);
                }
            }
            case "service" -> showServiceSource(v, hopIndex);
            case "dao" -> showDaoSource(v);
            case "bean" -> showBeanSource(v);
            default -> sourcePane.showFile(v.roots, v.row.definedIn, v.row.line);
        }
    }

    private void showBeanSource(PathRowView v) {
        PathRow r = v.row;
        if ((r.beanFile != null && !r.beanFile.isBlank())
                || SourceLocator.findJavaFile(v.roots, SourceLocator.simpleClass(r.beanMethod)) != null) {
            SourceLocator.showJava(sourcePane, v.roots, r.beanMethod, r.beanFile, r.beanLine);
        } else {
            sourcePane.showFile(v.roots, r.definedIn, r.line);
        }
    }

    private void showServiceSource(PathRowView v, int hopIndex) {
        PathRow r = v.row;
        String label = "";
        String file = "";
        int line = 0;
        if (hopIndex >= 0 && r.services != null && hopIndex < r.services.size()) {
            label = r.services.get(hopIndex);
            if (r.serviceFiles != null && hopIndex < r.serviceFiles.size()) file = r.serviceFiles.get(hopIndex);
            if (r.serviceLines != null && hopIndex < r.serviceLines.size()) line = r.serviceLines.get(hopIndex);
        } else if (r.services != null && !r.services.isEmpty()) {
            label = r.services.get(r.services.size() - 1);
            int i = r.services.size() - 1;
            if (r.serviceFiles != null && i < r.serviceFiles.size()) file = r.serviceFiles.get(i);
            if (r.serviceLines != null && i < r.serviceLines.size()) line = r.serviceLines.get(i);
        }
        SourceLocator.showJava(sourcePane, v.roots, label, file, line);
    }

    private void showDaoSource(PathRowView v) {
        PathRow r = v.row;
        if (r.mapperFile != null && !r.mapperFile.isBlank()) {
            SourceLocator.showJava(sourcePane, v.roots, r.mapperMethod, r.mapperFile, r.mapperLine);
            return;
        }
        if (r.sqlSource != null && !r.sqlSource.isBlank()) {
            sourcePane.showFile(v.roots, r.sqlSource, SourcePane.parseLineFromSource(r.sqlSource));
            return;
        }
        SourceLocator.showJava(sourcePane, v.roots, r.mapperMethod, "", 0);
    }

    private void addProject() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("解析対象プロジェクト（主フォルダ）を選択");
        if (!projectModel.isEmpty()) fc.setCurrentDirectory(projectModel.get(0).primary.toFile());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        for (int i = 0; i < projectModel.size(); i++) {
            if (projectModel.get(i).primary.equals(p)) {
                projectList.setSelectedIndex(i);
                return;
            }
        }
        RegisteredProject rp = new RegisteredProject(p);
        projectModel.addElement(rp);
        persistProjects();
        projectList.setSelectedValue(rp, true);
    }

    /** 選択中プロジェクトに DAO/SQL など別フォルダを紐づける */
    private void addRelatedFolder() {
        RegisteredProject sel = projectList.getSelectedValue();
        if (sel == null) {
            JOptionPane.showMessageDialog(this, "先にプロジェクトを選択してください", "確認", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("関連フォルダ（DAO / SQL など）を選択 — " + sel.displayName());
        fc.setCurrentDirectory(sel.primary.toFile());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path extra = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (extra.equals(sel.primary)) {
            JOptionPane.showMessageDialog(this, "主フォルダと同じパスは追加できません", "確認", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RegisteredProject updated = sel.withExtra(extra);
        int idx = projectList.getSelectedIndex();
        projectModel.set(idx, updated);
        persistProjects();
        projectList.setSelectedIndex(idx);
        status.setText("関連フォルダを追加: " + updated.displayName());
        loadXhtmlForSelection();
    }

    private void removeProjects() {
        List<RegisteredProject> sel = projectList.getSelectedValuesList();
        if (sel.isEmpty()) return;
        // 関連フォルダだけある場合は関連を外すダイアログ
        if (sel.size() == 1 && !sel.get(0).extras.isEmpty()) {
            RegisteredProject rp = sel.get(0);
            String[] opts = new String[rp.extras.size() + 1];
            opts[0] = "プロジェクト全体を削除";
            for (int i = 0; i < rp.extras.size(); i++) {
                Path e = rp.extras.get(i);
                opts[i + 1] = "関連を外す: " + (e.getFileName() != null ? e.getFileName() : e);
            }
            Object choice = JOptionPane.showInputDialog(this, "削除対象を選んでください", "削除",
                    JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (choice == null) return;
            if (choice.equals(opts[0])) {
                projectModel.removeElement(rp);
            } else {
                int ei = -1;
                for (int i = 1; i < opts.length; i++) if (choice.equals(opts[i])) { ei = i - 1; break; }
                if (ei >= 0) {
                    int idx = projectList.getSelectedIndex();
                    projectModel.set(idx, rp.withoutExtra(rp.extras.get(ei)));
                }
            }
        } else {
            for (RegisteredProject p : sel) projectModel.removeElement(p);
        }
        persistProjects();
        xhtmlModel.clear();
    }

    private void persistProjects() {
        store.save(allProjects());
    }

    private void browseOutDir() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        try { fc.setCurrentDirectory(Path.of(outField.getText()).toFile()); } catch (Exception ignore) { }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadXhtmlForSelection() {
        xhtmlModel.clear();
        List<RegisteredProject> sel = projectList.getSelectedValuesList();
        if (sel.isEmpty()) return;
        status.setText("xhtml 一覧を読込中…");
        analyzeBtn.setEnabled(false);
        new SwingWorker<List<XhtmlItem>, Void>() {
            @Override
            protected List<XhtmlItem> doInBackground() throws Exception {
                List<XhtmlItem> items = new ArrayList<>();
                for (RegisteredProject project : sel) {
                    try {
                        for (Facelet f : Analyzer.listScreens(project.primary, project.extras)) {
                            items.add(new XhtmlItem(project.primary, f));
                        }
                    } catch (Exception ex) {
                        items.add(new XhtmlItem(project.primary, null, "読込失敗: " + ex.getMessage()));
                    }
                }
                items.sort(Comparator.comparing(XhtmlItem::toString));
                return items;
            }

            @Override
            protected void done() {
                analyzeBtn.setEnabled(true);
                try {
                    for (XhtmlItem it : get()) xhtmlModel.addElement(it);
                    String msg = "画面候補 " + xhtmlModel.size() + " 件";
                    if (pathsModel.size() > 0) msg += " / ツリー " + pathsModel.size() + " パス";
                    status.setText(msg);
                } catch (Exception e) {
                    status.setText("xhtml 読込失敗: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void filter() {
        applySearch();
        refreshTrees();
    }

    private void setSearchTargets(boolean on) {
        for (JCheckBox cb : List.of(searchXhtml, searchButton, searchBean, searchService, searchDao, searchSqlTable)) {
            cb.setSelected(on);
        }
        filter();
    }

    private void applySearch() {
        SearchFields f = new SearchFields();
        f.xhtml = searchXhtml.isSelected();
        f.button = searchButton.isSelected();
        f.bean = searchBean.isSelected();
        f.service = searchService.isSelected();
        f.dao = searchDao.isSelected();
        f.sqlTable = searchSqlTable.isSelected();
        pathsModel.setSearchFields(f);
        pathsModel.setQuery(searchField.getText());
    }

    private void runAnalysis() {
        List<RegisteredProject> projects = projectList.getSelectedValuesList();
        if (projects.isEmpty()) projects = allProjects();
        if (projects.isEmpty()) {
            JOptionPane.showMessageDialog(this, "プロジェクトを追加してください", "確認", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Set<String> filters = new LinkedHashSet<>();
        if (!allXhtml.isSelected()) {
            for (XhtmlItem it : xhtmlList.getSelectedValuesList()) {
                if (it.facelet != null) filters.add(it.facelet.webPath);
            }
            if (filters.isEmpty()) {
                JOptionPane.showMessageDialog(this, "解析する xhtml を選択するか、「全画面を解析」にチェックを入れてください",
                        "確認", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        Path out = Path.of(outField.getText().trim());
        boolean merge = mergeResults.isSelected();
        analyzeBtn.setEnabled(false);
        status.setText("解析中…");
        List<RegisteredProject> targets = new ArrayList<>(projects);
        Set<String> xhtmlFilters = filters;

        new SwingWorker<List<PathRowView>, String>() {
            @Override
            protected List<PathRowView> doInBackground() throws Exception {
                List<PathRowView> views = new ArrayList<>();
                for (RegisteredProject project : targets) {
                    publish("解析中: " + project.displayName());
                    Analyzer.Options opt = new Analyzer.Options();
                    opt.project = project.primary;
                    opt.extraRoots.addAll(project.extras);
                    opt.out = targets.size() == 1 ? out : out.resolve(safeName(project.primary));
                    opt.xhtmlFilters.addAll(filterForProject(project.primary, xhtmlFilters));
                    opt.writeFiles = true;
                    opt.merge = merge;
                    Analyzer.Result r = Analyzer.run(opt, this::publish);
                    for (PathRow row : r.rows) views.add(new PathRowView(project, row));
                }
                return views;
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                analyzeBtn.setEnabled(true);
                try {
                    List<PathRowView> views = get();
                    Set<Path> touched = new HashSet<>();
                    for (RegisteredProject p : targets) touched.add(p.primary.toAbsolutePath().normalize());
                    List<PathRowView> kept = new ArrayList<>();
                    for (PathRowView v : pathsModel.snapshot()) {
                        if (!touched.contains(v.project.toAbsolutePath().normalize())) kept.add(v);
                    }
                    kept.addAll(views);
                    pathsModel.setRows(kept);
                    applySearch();
                    refreshTrees();
                    store.saveLastOut(out);
                    status.setText("完了: " + pathsModel.size() + " パス表示"
                            + (merge ? "（既存とマージ）" : "") + " / 出力 " + out);
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    status.setText("解析失敗");
                    JOptionPane.showMessageDialog(AnalyzerFrame.this, c.getMessage(), "解析エラー", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** 選択 xhtml のうち当該プロジェクト分だけフィルタに残す（全画面時は空＝制限なし） */
    private Set<String> filterForProject(Path project, Set<String> allFilters) {
        if (allFilters.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (XhtmlItem it : xhtmlList.getSelectedValuesList()) {
            if (it.project.equals(project) && it.facelet != null) out.add(it.facelet.webPath);
        }
        if (out.isEmpty()) out.addAll(allFilters);
        return out;
    }

    private static String safeName(Path project) {
        String n = project.getFileName() != null ? project.getFileName().toString() : "project";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** xhtml リスト項目 */
    static final class XhtmlItem {
        final Path project;
        final Facelet facelet;
        final String error;

        XhtmlItem(Path project, Facelet facelet) {
            this(project, facelet, null);
        }

        XhtmlItem(Path project, Facelet facelet, String error) {
            this.project = project;
            this.facelet = facelet;
            this.error = error;
        }

        @Override
        public String toString() {
            if (error != null) return "[" + project.getFileName() + "] " + error;
            return "[" + project.getFileName() + "] " + facelet.webPath + "  (" + facelet.rel + ")";
        }
    }
}
