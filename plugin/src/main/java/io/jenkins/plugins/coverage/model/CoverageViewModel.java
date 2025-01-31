package io.jenkins.plugins.coverage.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SortedMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.Fraction;

import edu.hm.hafner.echarts.TreeMapNode;

import org.kohsuke.stapler.bind.JavaScriptMethod;
import hudson.Functions;
import hudson.model.ModelObject;
import hudson.model.Run;
import hudson.util.TextFile;

import io.jenkins.plugins.coverage.model.visualization.code.SourceCodeFacade;
import io.jenkins.plugins.coverage.model.visualization.colorization.ColorProvider;
import io.jenkins.plugins.coverage.model.visualization.colorization.ColorProvider.DisplayColors;
import io.jenkins.plugins.coverage.model.visualization.colorization.ColorProviderFactory;
import io.jenkins.plugins.coverage.model.visualization.colorization.CoverageChangeTendency;
import io.jenkins.plugins.coverage.model.visualization.colorization.CoverageLevel;
import io.jenkins.plugins.coverage.model.visualization.tree.TreeMapNodeConverter;
import io.jenkins.plugins.datatables.DefaultAsyncTableContentProvider;
import io.jenkins.plugins.datatables.TableColumn;
import io.jenkins.plugins.datatables.TableColumn.ColumnCss;
import io.jenkins.plugins.datatables.TableConfiguration;
import io.jenkins.plugins.datatables.TableModel;
import io.jenkins.plugins.datatables.TableModel.DetailedColumnDefinition;
import io.jenkins.plugins.datatables.options.SelectStyle;
import io.jenkins.plugins.util.BuildResultNavigator;

import static j2html.TagCreator.*;

/**
 * Server side model that provides the data for the details view of the coverage results. The layout of the associated
 * view is defined corresponding jelly view 'index.jelly'.
 *
 * @author Ullrich Hafner
 * @author Florian Orendi
 */
@SuppressWarnings("PMD.ExcessivePublicCount")
public class CoverageViewModel extends DefaultAsyncTableContentProvider implements ModelObject {
    private static final CoverageMetric LINE_COVERAGE = CoverageMetric.LINE;
    private static final CoverageMetric BRANCH_COVERAGE = CoverageMetric.BRANCH;
    private static final ColorProvider COLOR_PROVIDER = ColorProviderFactory.createColorProvider();
    private static final TreeMapNodeConverter TREE_MAP_NODE_CONVERTER = new TreeMapNodeConverter(COLOR_PROVIDER);
    private static final BuildResultNavigator NAVIGATOR = new BuildResultNavigator();
    private static final SourceCodeFacade SOURCE_CODE_FACADE = new SourceCodeFacade();

    static final String CHANGE_COVERAGE_TABLE_ID = "change-coverage-table";
    static final String COVERAGE_CHANGES_TABLE_ID = "coverage-changes-table";

    private final Run<?, ?> owner;
    private final CoverageNode node;
    private final String id;

    private final CoverageNode changeCoverageTreeRoot;
    private final CoverageNode indirectCoverageChangesTreeRoot;

    /**
     * Creates a new view model instance.
     *
     * @param owner
     *         the owner of this view
     * @param node
     *         the coverage node to be shown
     */
    public CoverageViewModel(final Run<?, ?> owner, final CoverageNode node) {
        super();

        this.owner = owner;
        this.node = node;
        this.id = "coverage"; // TODO: this needs to be a parameter

        // initialize filtered coverage trees so that they will not be calculated multiple times
        this.changeCoverageTreeRoot = node.getChangeCoverageTree();
        this.indirectCoverageChangesTreeRoot = node.getIndirectCoverageChangesTree();
    }

    public String getId() {
        return id;
    }

    public Run<?, ?> getOwner() {
        return owner;
    }

    public CoverageNode getNode() {
        return node;
    }

    @Override
    public String getDisplayName() {
        return Messages.Coverage_Title(node.getName());
    }

    @JavaScriptMethod
    public CoverageOverview getOverview() {
        return new CoverageOverview(getNode().filterPackageStructure());
    }

    @JavaScriptMethod
    public CoverageOverview getChangeCoverageOverview() {
        return new CoverageOverview(changeCoverageTreeRoot.filterPackageStructure());
    }

    /**
     * Returns the root of the tree of nodes for the ECharts treemap. This tree is used as model for the chart on the
     * client side. The tree is available for line and branch coverage.
     *
     * @param coverageMetric
     *         The used coverage metric - the default is the line coverage
     *
     * @return the tree of nodes for the ECharts treemap
     */
    @JavaScriptMethod
    @SuppressWarnings("unused")
    public TreeMapNode getCoverageTree(final String coverageMetric) {
        CoverageMetric metric = getCoverageMetricFromText(coverageMetric);
        return TREE_MAP_NODE_CONVERTER.toTeeChartModel(getNode(), metric);
    }

    /**
     * Returns the root of the filtered tree of change coverage nodes for the ECharts treemap. This tree is used as
     * model for the chart on the client side. The tree is available for line and branch coverage.
     *
     * @param coverageMetric
     *         The used coverage metric - the default is the line coverage
     *
     * @return the tree of change coverage nodes for the ECharts treemap
     */
    @JavaScriptMethod
    @SuppressWarnings("unused")
    public TreeMapNode getChangeCoverageTree(final String coverageMetric) {
        CoverageMetric metric = getCoverageMetricFromText(coverageMetric);
        return TREE_MAP_NODE_CONVERTER.toTeeChartModel(changeCoverageTreeRoot, metric);
    }

    /**
     * Returns the root of the filtered tree of indirect coverage changes for the ECharts treemap. This tree is used as
     * model for the chart on the client side. The tree is available for line and branch coverage.
     *
     * @param coverageMetric
     *         The used coverage metric - the default is the line coverage
     *
     * @return the tree of indirect coverage changes nodes for the ECharts treemap
     */
    @JavaScriptMethod
    @SuppressWarnings("unused")
    public TreeMapNode getCoverageChangesTree(final String coverageMetric) {
        CoverageMetric metric = getCoverageMetricFromText(coverageMetric);
        return TREE_MAP_NODE_CONVERTER.toTeeChartModel(indirectCoverageChangesTreeRoot, metric);
    }

    /**
     * Gets the {@link CoverageMetric} from a String representation used in the frontend. Only 'Line' and 'Branch' is
     * possible. 'Line' is used as a default.
     *
     * @param text
     *         The coverage metric as String
     * @return the coverage metric
     */
    private CoverageMetric getCoverageMetricFromText(final String text) {
        if ("Branch".equals(text)) {
            return CoverageMetric.BRANCH;
        }
        return CoverageMetric.LINE;
    }

    /**
     * Returns the table model that matches with the passed table ID and shows the files along with the branch and line
     * coverage.
     *
     * @param tableId
     *         ID of the table model
     *
     * @return the table model with the specified ID
     */
    @Override
    public TableModel getTableModel(final String tableId) {
        CoverageNode root = getNode();
        if (CHANGE_COVERAGE_TABLE_ID.equals(tableId)) {
            return new ChangeCoverageTable(root, changeCoverageTreeRoot, tableId);
        }
        else if (COVERAGE_CHANGES_TABLE_ID.equals(tableId)) {
            return new IndirectCoverageChangesTable(root, indirectCoverageChangesTreeRoot, tableId);
        }
        return new CoverageTableModel(root, tableId);
    }

    /**
     * Returns the URL for coverage results of the selected build. Based on the current URL, the new URL will be
     * composed by replacing the current build number with the selected build number.
     *
     * @param selectedBuildDisplayName
     *         the selected build to open the new results for
     * @param currentUrl
     *         the absolute URL to this details view results
     *
     * @return the URL to the results or an empty string if the results are not available
     */
    @JavaScriptMethod
    public String getUrlForBuild(final String selectedBuildDisplayName, final String currentUrl) {
        return NAVIGATOR.getSameUrlForOtherBuild(owner,
                currentUrl, CoverageBuildAction.DETAILS_URL, selectedBuildDisplayName)
                .orElse(StringUtils.EMPTY);
    }

    /**
     * Gets the source code of the file which is represented by the passed hash code. The coverage of the source code is
     * highlighted by using HTML. Depending on the passed table ID, the source code is returned filtered with only the
     * relevant lines of code.
     *
     * @param fileHash
     *         The hash code of the requested file
     * @param tableId
     *         The ID of the source file table
     *
     * @return the highlighted source code
     */
    @JavaScriptMethod
    public String getSourceCode(final String fileHash, final String tableId) {
        Optional<CoverageNode> targetResult
                = getNode().findByHashCode(CoverageMetric.FILE, Integer.parseInt(fileHash));
        if (targetResult.isPresent()) {
            try {
                CoverageNode fileNode = targetResult.get();
                return readSourceCode(fileNode, tableId);
            }
            catch (IOException | InterruptedException exception) {
                return ExceptionUtils.getStackTrace(exception);
            }
        }
        return Messages.Coverage_Not_Available();
    }

    /**
     * Reads the sourcecode corresponding to the passed {@link CoverageNode node} and filters the code dependent on the
     * table ID.
     *
     * @param fileNode
     *         The node
     * @param tableId
     *         The table ID
     *
     * @return the sourcecode with highlighted coverage
     * @throws IOException
     *         if reading failed
     * @throws InterruptedException
     *         if reading failed
     */
    private String readSourceCode(final CoverageNode fileNode, final String tableId)
            throws IOException, InterruptedException {
        String content = "";
        File rootDir = getOwner().getRootDir();
        if (isSourceFileInNewFormatAvailable(fileNode)) {
            content = SOURCE_CODE_FACADE.read(rootDir, getId(), fileNode.getPath());
        }
        if (isSourceFileInOldFormatAvailable(fileNode)) {
            content = new TextFile(getFileForBuildsWithOldVersion(rootDir,
                    fileNode.getName())).read(); // fallback with sources persisted using the < 2.1.0 serialization
        }
        if (!content.isEmpty() && fileNode instanceof FileCoverageNode) {
            if (CHANGE_COVERAGE_TABLE_ID.equals(tableId)) {
                return SOURCE_CODE_FACADE.calculateChangeCoverageSourceCode(content, (FileCoverageNode) fileNode);
            }
            else if (COVERAGE_CHANGES_TABLE_ID.equals(tableId)) {
                return SOURCE_CODE_FACADE.calculateIndirectCoverageChangesSourceCode(content,
                        (FileCoverageNode) fileNode);
            }
            else {
                return content;
            }
        }
        return Messages.Coverage_Not_Available();
    }

    /**
     * Checks whether there are stored source files.
     *
     * @return {@code true} whether there are stored source files, else {@code false}
     * @since 3.0.0
     */
    @JavaScriptMethod
    public boolean hasStoredSourceCode() {
        return SOURCE_CODE_FACADE.hasStoredSourceCode(getOwner().getRootDir(), id);
    }

    /**
     * Checks whether change coverage exists.
     *
     * @return {@code true} whether change coverage exists, else {@code false}
     */
    public boolean hasChangeCoverage() {
        return getNode().hasChangeCoverage();
    }

    /**
     * Checks whether indirect coverage changes exist.
     *
     * @return {@code true} whether indirect coverage changes exist, else {@code false}
     */
    public boolean hasIndirectCoverageChanges() {
        return getNode().hasIndirectCoverageChanges();
    }

    /**
     * Returns whether the source file is available in Jenkins build folder.
     *
     * @param coverageNode
     *         The {@link CoverageNode} which is checked if there is a source file available
     *
     * @return {@code true} if the source file is available, {@code false} otherwise
     */
    public boolean isSourceFileAvailable(final CoverageNode coverageNode) {
        return isSourceFileInNewFormatAvailable(coverageNode) || isSourceFileInOldFormatAvailable(coverageNode);
    }

    /**
     * Returns whether the source file is available in Jenkins build folder in the old format of the plugin versions
     * less than 2.1.0.
     *
     * @param coverageNode
     *         The {@link CoverageNode} which is checked if there is a source file available
     *
     * @return {@code true} if the source file is available, {@code false} otherwise
     */
    public boolean isSourceFileInOldFormatAvailable(final CoverageNode coverageNode) {
        return isSourceFileInOldFormatAvailable(getOwner().getRootDir(), coverageNode.getName());
    }

    static boolean isSourceFileInOldFormatAvailable(final File rootDir, final String nodeName) {
        return getFileForBuildsWithOldVersion(rootDir, nodeName).canRead();
    }

    /**
     * Returns a file to the sources in release in the old format of the plugin versions less than 2.1.0.
     *
     * @param buildFolder
     *         top-level folder of the build results
     * @param fileName
     *         base filename of the coverage node
     *
     * @return the file
     */
    public static File getFileForBuildsWithOldVersion(final File buildFolder, final String fileName) {
        return new File(new File(buildFolder, "coverage-sources"), sanitizeFilename(fileName));
    }

    private static String sanitizeFilename(final String inputName) {
        return inputName.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }

    /**
     * Returns whether the source file is available in Jenkins build folder in the new format of the plugin versions
     * greater or equal than 2.1.0.
     *
     * @param coverageNode
     *         The {@link CoverageNode} which is checked if there is a source file available
     *
     * @return {@code true} if the source file is available, {@code false} otherwise
     */
    public boolean isSourceFileInNewFormatAvailable(final CoverageNode coverageNode) {
        return isSourceFileInNewFormatAvailable(getOwner().getRootDir(), id, coverageNode.getPath());
    }

    static boolean isSourceFileInNewFormatAvailable(final File rootDir, final String id, final String nodePath) {
        return SOURCE_CODE_FACADE.createFileInBuildFolder(rootDir, id, nodePath).canRead();
    }

    /**
     * UI model for the coverage overview bar chart. Shows the coverage results for the different coverage metrics.
     */
    public static class CoverageOverview {
        private final CoverageNode coverage;

        CoverageOverview(final CoverageNode coverage) {
            this.coverage = coverage;
        }

        public List<String> getMetrics() {
            return getMetricsDistribution().keySet().stream()
                    .skip(1) // ignore the root of the tree as the coverage is always 1 of 1
                    .map(CoverageMetric::getName)
                    .collect(Collectors.toList());
        }

        public List<Integer> getCovered() {
            return streamCoverages().map(Coverage::getCovered).collect(Collectors.toList());
        }

        public List<Double> getCoveredPercentages() {
            return streamCoverages().map(Coverage::getCoveredFraction)
                    .map(Fraction::doubleValue)
                    .collect(Collectors.toList());
        }

        public List<Integer> getMissed() {
            return streamCoverages().map(Coverage::getMissed).collect(Collectors.toList());
        }

        public List<Double> getMissedPercentages() {
            return streamCoverages().map(Coverage::getMissedFraction)
                    .map(Fraction::doubleValue)
                    .collect(Collectors.toList());
        }

        private Stream<Coverage> streamCoverages() {
            return getMetricsDistribution().values().stream().skip(1);
        }

        private SortedMap<CoverageMetric, Coverage> getMetricsDistribution() {
            return coverage.getMetricsDistribution();
        }
    }

    /**
     * UI table model for the coverage details table.
     */
    static class CoverageTableModel extends TableModel {
        private final CoverageNode root;
        private final String id;

        CoverageTableModel(final CoverageNode root, final String id) {
            super();

            this.root = root;
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public TableConfiguration getTableConfiguration() {
            TableConfiguration tableConfiguration = new TableConfiguration();
            tableConfiguration.select(SelectStyle.SINGLE);
            return tableConfiguration;
        }

        @Override
        public List<TableColumn> getColumns() {
            List<TableColumn> columns = new ArrayList<>();

            // TODO: DataTables API sets options wrong in case of invisible columns (columnDef and <th> number not matching)
            // TODO: DataTables API requires more granular width options

            // this column is hidden, but used to access the file hash from the frontend
            TableColumn fileHashColumn = new TableColumn("Hash", "fileHash");
            fileHashColumn.setHeaderClass(ColumnCss.HIDDEN);
            // fileHashColumn.setWidth(0);
            columns.add(fileHashColumn);

            TableColumn packageColumn = new TableColumn("Package", "packageName");
            // packageColumn.setWidth(2);
            columns.add(packageColumn);

            TableColumn fileColumn = new TableColumn("File", "fileName");
            // fileColumn.setWidth(2);
            columns.add(fileColumn);

            TableColumn lineColumn = new TableColumn("Line", "lineCoverage", "number");
            // lineColumn.setWidth(2);
            columns.add(lineColumn);

            TableColumn lineColumnDelta = new TableColumn("Line Δ", "lineCoverageDelta", "number");
            // lineColumnDelta.setWidth(1);
            columns.add(lineColumnDelta);

            TableColumn branchColumn = new TableColumn("Branch", "branchCoverage", "number");
            // branchColumn.setWidth(2);
            columns.add(branchColumn);

            TableColumn branchColumnDelta = new TableColumn("Branch Δ", "branchCoverageDelta", "number");
            // branchColumnDelta.setWidth(1);
            columns.add(branchColumnDelta);

            TableColumn loc = new TableColumn("LOC", "loc", "number");
            // loc.setWidth(1);
            columns.add(loc);

            return columns;
        }

        @Override
        public List<Object> getRows() {
            Locale browserLocale = Functions.getCurrentLocale();
            return root.getAll(CoverageMetric.FILE).stream()
                    .map(file -> new CoverageRow(file, browserLocale))
                    .collect(Collectors.toList());
        }

        protected CoverageNode getRoot() {
            return root;
        }
    }

    /**
     * UI row model for the coverage details table.
     */
    private static class CoverageRow {
        private final CoverageNode root;
        private final Locale browserLocale;

        CoverageRow(final CoverageNode root, final Locale browserLocale) {
            this.root = root;
            this.browserLocale = browserLocale;
        }

        public String getFileHash() {
            return String.valueOf(root.getName().hashCode());
        }

        public String getFileName() {
            return root.getName();
        }

        public String getPackageName() {
            return root.getParentName();
        }

        public DetailedColumnDefinition getLineCoverage() {
            Coverage coverage = root.getCoverage(LINE_COVERAGE);
            return createColoredCoverageColumn(coverage.getCoveredPercentage(), printCoverage(coverage),
                    "The total line coverage of the file");
        }

        public DetailedColumnDefinition getBranchCoverage() {
            Coverage coverage = root.getCoverage(BRANCH_COVERAGE);
            return createColoredCoverageColumn(coverage.getCoveredPercentage(), printCoverage(coverage),
                    "The total branch coverage of the file");
        }

        public DetailedColumnDefinition getLineCoverageDelta() {
            return createColoredFileCoverageDeltaColumn(LINE_COVERAGE);
        }

        public DetailedColumnDefinition getBranchCoverageDelta() {
            return createColoredFileCoverageDeltaColumn(BRANCH_COVERAGE);
        }

        public DetailedColumnDefinition getLoc() {
            if (root instanceof FileCoverageNode) {
                String value = String.valueOf(((FileCoverageNode) root).getCoveragePerLine().size());
                return new DetailedColumnDefinition(value, value);
            }
            return new DetailedColumnDefinition(Messages.Coverage_Not_Available(), "0");
        }

        protected String printCoverage(final Coverage coverage) {
            if (coverage.isSet()) {
                return coverage.formatCoveredPercentage(browserLocale);
            }
            return Messages.Coverage_Not_Available();
        }

        /**
         * Creates a table cell which colorizes the shown coverage dependent on the coverage percentage.
         *
         * @param coverage
         *         The coverage as percentage
         * @param text
         *         The text to be shown which represents the coverage
         * @param tooltip
         *         The tooltip which describes the value
         *
         * @return the create {@link DetailedColumnDefinition}
         */
        protected DetailedColumnDefinition createColoredCoverageColumn(final CoveragePercentage coverage,
                final String text, final String tooltip) {
            double percentage = coverage.getDoubleValue();
            String sort = String.valueOf(percentage);
            DisplayColors colors = CoverageLevel.getDisplayColorsOfCoverageLevel(percentage, COLOR_PROVIDER);
            String tag = span()
                    .withTitle(tooltip)
                    .withStyle(String.format(
                            "color:%s; background-image: linear-gradient(90deg, %s %f%%, transparent %f%%); display:block;",
                            colors.getLineColorAsHex(), colors.getFillColorAsHex(),
                            percentage, percentage))
                    .withText(text)
                    .render();
            return new DetailedColumnDefinition(tag, sort);
        }

        /**
         * Creates a table cell which colorizes the tendency of the shown coverage delta.
         *
         * @param coveragePercentage
         *         The coverage delta as percentage
         * @param tooltip
         *         The tooltip which describes the value
         *
         * @return the create {@link DetailedColumnDefinition}
         */
        protected DetailedColumnDefinition createColoredCoverageDeltaColumn(
                final CoveragePercentage coveragePercentage, final String tooltip) {
            double coverageValue = coveragePercentage.getDoubleValue();
            String coverageText = coveragePercentage.formatDeltaPercentage(browserLocale);
            String sort = String.valueOf(coverageValue);
            DisplayColors colors = CoverageChangeTendency
                    .getDisplayColorsForTendency(coverageValue, COLOR_PROVIDER);
            String tag = span()
                    .withClasses("badge", "badge-delta")
                    .withStyle(String.format("color:%s;background-color:%s;",
                            colors.getLineColorAsHex(), colors.getFillColorAsHex()))
                    .withText(coverageText)
                    .withTitle(tooltip)
                    .render();
            return new DetailedColumnDefinition(tag, sort);
        }

        protected CoverageNode getRoot() {
            return root;
        }

        /**
         * Creates a colored column for visualizing the file coverage delta against a reference for the passed {@link
         * CoverageMetric}.
         *
         * @param coverageMetric
         *         The coverage metric
         *
         * @return the {@link DetailedColumnDefinition}
         * @since 3.0.0
         */
        private DetailedColumnDefinition createColoredFileCoverageDeltaColumn(final CoverageMetric coverageMetric) {
            // this is only available for versions later then 3.0.0 which introduced FileCoverageNode
            if (root instanceof FileCoverageNode) {
                FileCoverageNode fileNode = (FileCoverageNode) root;
                if (fileNode.hasFileCoverageDelta(coverageMetric)) {
                    CoveragePercentage delta = fileNode.getFileCoverageDeltaForMetric(coverageMetric);
                    return createColoredCoverageDeltaColumn(delta,
                            "The total file coverage delta against the reference build");
                }
            }
            return new DetailedColumnDefinition(Messages.Coverage_Not_Available(), "-101");
        }
    }

    /**
     * {@link CoverageTableModel} implementation for visualizing the change coverage.
     *
     * @since 3.0.0
     */
    static class ChangeCoverageTable extends CoverageTableModel {

        private final CoverageNode changeRoot;

        /**
         * Creates a change coverage table model.
         *
         * @param root
         *         The root of the origin coverage tree
         * @param changeRoot
         *         The root of the change coverage tree
         * @param id
         *         The ID of the table
         */
        ChangeCoverageTable(final CoverageNode root, final CoverageNode changeRoot, final String id) {
            super(root, id);
            this.changeRoot = changeRoot;
        }

        @Override
        public List<Object> getRows() {
            Locale browserLocale = Functions.getCurrentLocale();
            return changeRoot.getAllFileCoverageNodes().stream()
                    .map(file -> new ChangeCoverageRow(getOriginalNode(file), file, browserLocale))
                    .collect(Collectors.toList());
        }

        private FileCoverageNode getOriginalNode(final FileCoverageNode fileNode) {
            Optional<FileCoverageNode> reference = getRoot().getAllFileCoverageNodes().stream()
                    .filter(node -> node.getPath().equals(fileNode.getPath())
                            && node.getName().equals(fileNode.getName()))
                    .findFirst();
            return reference.orElse(fileNode); // return this as fallback to prevent exceptions
        }
    }

    /**
     * {@link CoverageTableModel} implementation for visualizing the indirect coverage changes.
     *
     * @since 3.0.0
     */
    static class IndirectCoverageChangesTable extends CoverageTableModel {

        private final CoverageNode changeRoot;

        /**
         * Creates a indirect coverage changes table model.
         *
         * @param root
         *         The root of the origin coverage tree
         * @param changeRoot
         *         The root of the indirect coverage changes tree
         * @param id
         *         The ID of the table
         */
        IndirectCoverageChangesTable(final CoverageNode root, final CoverageNode changeRoot, final String id) {
            super(root, id);
            this.changeRoot = changeRoot;
        }

        @Override
        public List<Object> getRows() {
            Locale browserLocale = Functions.getCurrentLocale();
            return changeRoot.getAllFileCoverageNodes().stream()
                    .map(file -> new IndirectCoverageChangesRow(getOriginalNode(file), file, browserLocale))
                    .collect(Collectors.toList());
        }

        private FileCoverageNode getOriginalNode(final FileCoverageNode fileNode) {
            Optional<FileCoverageNode> reference = getRoot().getAllFileCoverageNodes().stream()
                    .filter(node -> node.getPath().equals(fileNode.getPath())
                            && node.getName().equals(fileNode.getName()))
                    .findFirst();
            return reference.orElse(fileNode); // return this as fallback to prevent exceptions
        }
    }

    /**
     * UI row model for the change coverage details table.
     *
     * @since 3.0.0
     */
    private static class ChangeCoverageRow extends CoverageRow {

        private final FileCoverageNode changedFileNode;

        /**
         * Creates a table row for visualizing the change coverage of a file.
         *
         * @param root
         *         The unfiltered node which represents the coverage of the whole file
         * @param changedFileNode
         *         The filtered node which represents the change coverage only
         * @param browserLocale
         *         The locale
         */
        ChangeCoverageRow(final FileCoverageNode root, final FileCoverageNode changedFileNode,
                final Locale browserLocale) {
            super(root, browserLocale);
            this.changedFileNode = changedFileNode;
        }

        @Override
        public DetailedColumnDefinition getLineCoverage() {
            Coverage coverage = changedFileNode.getCoverage(LINE_COVERAGE);
            return createColoredCoverageColumn(coverage.getCoveredPercentage(), printCoverage(coverage),
                    "The line change coverage");
        }

        @Override
        public DetailedColumnDefinition getBranchCoverage() {
            Coverage coverage = changedFileNode.getCoverage(BRANCH_COVERAGE);
            return createColoredCoverageColumn(coverage.getCoveredPercentage(), printCoverage(coverage),
                    "The branch change coverage");
        }

        @Override
        public DetailedColumnDefinition getLineCoverageDelta() {
            return createColoredChangeCoverageDeltaColumn(LINE_COVERAGE);
        }

        @Override
        public DetailedColumnDefinition getBranchCoverageDelta() {
            return createColoredChangeCoverageDeltaColumn(BRANCH_COVERAGE);
        }

        @Override
        public DetailedColumnDefinition getLoc() {
            String value = String.valueOf(changedFileNode.getChangedCodeLines().stream()
                    .filter(line -> changedFileNode.getCoveragePerLine().containsKey(line))
                    .count());
            return new DetailedColumnDefinition(value, value);
        }

        private DetailedColumnDefinition createColoredChangeCoverageDeltaColumn(final CoverageMetric coverageMetric) {
            Coverage changeCoverage = changedFileNode.getCoverage(coverageMetric);
            if (changeCoverage.isSet()) {
                Fraction delta = changeCoverage.getCoveredFraction()
                        .subtract(getRoot().getCoverage(coverageMetric).getCoveredFraction());
                return createColoredCoverageDeltaColumn(CoveragePercentage.valueOf(delta),
                        "The change coverage within the file against the total file coverage");
            }
            return new DetailedColumnDefinition(Messages.Coverage_Not_Available(), "-101");
        }
    }

    /**
     * UI row model for the indirect coverage changes details table.
     *
     * @since 3.0.0
     */
    private static class IndirectCoverageChangesRow extends CoverageRow {

        private final FileCoverageNode changedFileNode;

        /**
         * Creates a table row for visualizing the indirect coverage changes of a file.
         *
         * @param root
         *         The unfiltered node which represents the coverage of the whole file
         * @param changedFileNode
         *         The filtered node which represents the indirect coverage changes only
         * @param browserLocale
         *         The locale
         */
        IndirectCoverageChangesRow(final FileCoverageNode root, final FileCoverageNode changedFileNode,
                final Locale browserLocale) {
            super(root, browserLocale);
            this.changedFileNode = changedFileNode;
        }

        @Override
        public DetailedColumnDefinition getLineCoverage() {
            Coverage coverage = changedFileNode.getCoverage(LINE_COVERAGE);
            return createColoredCoverageColumn(coverage.getCoveredPercentage(), printCoverage(coverage),
                    "The indirect line coverage changes");
        }

        @Override
        public DetailedColumnDefinition getBranchCoverage() {
            Coverage coverage = changedFileNode.getCoverage(BRANCH_COVERAGE);
            return createColoredCoverageColumn(coverage.getCoveredPercentage(), printCoverage(coverage),
                    "The indirect branch coverage changes");
        }

        @Override
        public DetailedColumnDefinition getLineCoverageDelta() {
            return createColoredChangeCoverageDeltaColumn(LINE_COVERAGE);
        }

        @Override
        public DetailedColumnDefinition getBranchCoverageDelta() {
            return createColoredChangeCoverageDeltaColumn(BRANCH_COVERAGE);
        }

        @Override
        public DetailedColumnDefinition getLoc() {
            String value = String.valueOf(changedFileNode.getIndirectCoverageChanges().size());
            return new DetailedColumnDefinition(value, value);
        }

        private DetailedColumnDefinition createColoredChangeCoverageDeltaColumn(
                final CoverageMetric coverageMetric) {
            Coverage changeCoverage = changedFileNode.getCoverage(coverageMetric);
            if (changeCoverage.isSet()) {
                Fraction delta = changeCoverage.getCoveredFraction()
                        .subtract(getRoot().getCoverage(coverageMetric).getCoveredFraction());
                return createColoredCoverageDeltaColumn(CoveragePercentage.valueOf(delta),
                        "The indirect coverage changes within the file against the total file coverage");
            }
            return new DetailedColumnDefinition(Messages.Coverage_Not_Available(), "-101");
        }
    }
}
