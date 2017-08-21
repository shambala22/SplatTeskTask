import org.apache.commons.validator.routines.UrlValidator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileSystemView;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ForkJoinPool;

/**
 * Created by shambala on 09.08.17.
 */
public class LogSearch {
    public static final String TITLE = "Log search";
    private JTree fileTree;
    private DefaultTreeModel treeModel;
    private FileSystemView fileSystemView;
    private JPanel gui;
    private JPanel fileView;
    private JTextArea fileText;
    private JFileChooser chooser;
    private JTextField pathField;
    private JTextField extensionField;
    private JProgressBar progressBar;

    private File searchPath = new File("/");

    private final ForkJoinPool forkJoinPool = new ForkJoinPool();
    private final UrlValidator urlValidator = new UrlValidator();
    private final DefaultHighlighter.DefaultHighlightPainter yellowHighlightPainter =
            new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW);
    private final DefaultHighlighter.DefaultHighlightPainter orangeHighlightPainter =
            new DefaultHighlighter.DefaultHighlightPainter(Color.ORANGE);

    private java.util.List<Integer> positions;
    private String lastRequest;
    private Map<Integer, Highlighter.Highlight> highlights;
    private SwingWorker<Void, Integer> loadFileWorker;
    private SwingWorker<Void, DefaultMutableTreeNode> searchFilesWorker;
    private int positionsIndex = 0;

    private Container getGUI() {
        gui = new JPanel(new BorderLayout(3,3));
        gui.setBorder(new EmptyBorder(5,5,5,5));
        fileSystemView = FileSystemView.getFileSystemView();

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new GridBagLayout());

        fileView = new JPanel(new BorderLayout(3, 3));
        fileText = new JTextArea();
        fileText.setWrapStyleWord(true);
        fileText.setLineWrap(true);
        fileText.setEditable(false);
        fileView.add(new JScrollPane(fileText), BorderLayout.CENTER);

        JButton previousButton = new JButton("Previous");
        JButton nextButton = new JButton("Next");
        JPanel navigationButtons = new JPanel(new GridBagLayout());
        GridBagConstraints buttonsConstraints = new GridBagConstraints();
        buttonsConstraints.weightx = 1.0;
        buttonsConstraints.fill = GridBagConstraints.HORIZONTAL;
        navigationButtons.add(previousButton, buttonsConstraints);
        navigationButtons.add(nextButton, buttonsConstraints);
        previousButton.addActionListener(event -> changeSelection(false));
        nextButton.addActionListener(event -> changeSelection(true));
        fileView.add(navigationButtons, BorderLayout.NORTH);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        treeModel = new DefaultTreeModel(root);

        JTextField searchField = new JTextField();
        searchField.addActionListener(actionEvent -> {
            if (new File(pathField.getText()).exists()) {
                searchPath = new File(pathField.getText());
            } else if (urlValidator.isValid(pathField.getText())) {
                try {
                    searchPath = new File(new URI(pathField.getText()));
                } catch (URISyntaxException e) {
                    JOptionPane.showMessageDialog(gui, "Path doesn't exist");
                } catch (IllegalArgumentException e) {
                    JOptionPane.showMessageDialog(gui, "This URI is not file");
                }
            } else {
                JOptionPane.showMessageDialog(gui, "Path doesn't exist");
                return;
            }
            if (searchPath.isDirectory()) {
                lastRequest = searchField.getText();
                if (searchFilesWorker != null && !searchFilesWorker.isDone()) {
                    searchFilesWorker.cancel(true);
                }
                searchFilesWorker = searchFiles(root, lastRequest, new File(pathField.getText()), extensionField.getText());
                searchField.setText("");
                searchFilesWorker.execute();
            } else {
                JOptionPane.showMessageDialog(gui, "Path is not a directory");
            }
        });
        pathField = new JTextField("/");

        JButton selectPath = new JButton("Select path");
        selectPath.addActionListener(event -> {
            chooser = new JFileChooser();
            chooser.setCurrentDirectory(searchPath);
            chooser.setDialogTitle("Choose directory");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(gui) == JFileChooser.APPROVE_OPTION) {
                searchPath = chooser.getSelectedFile();
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });


        GridBagConstraints left = new GridBagConstraints();
        left.anchor = GridBagConstraints.EAST;
        GridBagConstraints right = new GridBagConstraints();
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.gridwidth = GridBagConstraints.REMAINDER;
        topPanel.add(new JLabel("Search "), left);
        topPanel.add(searchField, right);
        extensionField = new JTextField("log");
        topPanel.add(new JLabel("Extension "), left);
        topPanel.add(extensionField, right);
        topPanel.add(selectPath, left);
        topPanel.add(pathField, right);

        fileTree = new JTree(treeModel);
        fileTree.setRootVisible(false);
        fileTree.addTreeSelectionListener(treeSelectionEvent -> {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode)treeSelectionEvent.getPath().getLastPathComponent();
            if (!((File)node.getUserObject()).isDirectory()) {
                if (loadFileWorker != null && !loadFileWorker.isDone()) {
                    loadFileWorker.cancel(true);
                }
                loadFileWorker = loadFile((File) node.getUserObject());
                loadFileWorker.execute();
            }
        });
        fileTree.setCellRenderer(new FileTreeCellRenderer());

        JScrollPane treeScroll = new JScrollPane(fileTree);
        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                treeScroll,
                fileView);
        splitPane.setDividerLocation(200);
        gui.add(topPanel, BorderLayout.NORTH);
        gui.add(splitPane, BorderLayout.CENTER);
        progressBar = new JProgressBar();
        gui.add(progressBar, BorderLayout.SOUTH);
        return gui;
    }

    private void changeSelection(boolean isIncrement) {
        if (positions.size() > 0) {
            try {
                fileText.getHighlighter().removeHighlight(highlights.get(positions.get(positionsIndex)));
                fileText.getHighlighter().addHighlight(positions.get(positionsIndex), positions.get(positionsIndex) + lastRequest.length(),
                        yellowHighlightPainter);
                highlights.put(positions.get(positionsIndex), fileText.getHighlighter().getHighlights()[fileText.getHighlighter().getHighlights().length - 1]);
                if (isIncrement) {
                    positionsIndex++;
                } else positionsIndex--;
                if (positionsIndex >= positions.size()) {
                    positionsIndex = 0;
                }
                if (positionsIndex < 0) {
                    positionsIndex = positions.size() - 1;
                }
                fileText.getHighlighter().removeHighlight(highlights.get(positions.get(positionsIndex)));
                fileText.getHighlighter().addHighlight(positions.get(positionsIndex), positions.get(positionsIndex) + lastRequest.length(),
                        orangeHighlightPainter);
                highlights.put(positions.get(positionsIndex), fileText.getHighlighter().getHighlights()[fileText.getHighlighter().getHighlights().length - 1]);
                fileText.setCaretPosition(positions.get(positionsIndex));
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }
    }


    private SwingWorker<Void, DefaultMutableTreeNode> searchFiles(final DefaultMutableTreeNode root, String searchRequest, File path, final String extension) {
        fileTree.setEnabled(false);
        progressBar.setIndeterminate(true);
        root.removeAllChildren();
        return new SwingWorker<Void, DefaultMutableTreeNode>() {
            @Override
            protected Void doInBackground() throws Exception {
                forkJoinPool.invoke(new SearchTask(path, searchRequest, extension, fileSystemView)).ifPresent(result -> {
                    while (result.children().hasMoreElements()) {
                        root.add((DefaultMutableTreeNode)result.children().nextElement());
                    }
                });
                return null;
            }

            @Override
            protected void done() {
                treeModel.reload(root);
                fileTree.setEnabled(true);
                progressBar.setIndeterminate(false);
            }
        };
    }

    private SwingWorker<Void, Integer> loadFile(File file) {
        highlights = new HashMap<>();
        progressBar.setIndeterminate(true);
        return new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    fileText.getHighlighter().removeAllHighlights();
                    fileText.read(reader, null);
                    Document document = fileText.getDocument();
                    for (int index = 0; index + lastRequest.length() < document.getLength(); index++) {
                        String match = document.getText(index, lastRequest.length());
                        if (lastRequest.equals(match)) {
                            publish(index);
                        }
                    }
                } catch (BadLocationException | IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                positions = new ArrayList<>(chunks);
                try {
                    for (int position : positions) {
                        fileText.getHighlighter().addHighlight(position, position + lastRequest.length(),
                                yellowHighlightPainter);
                        Highlighter.Highlight[] currentHighlights = fileText.getHighlighter().getHighlights();
                        highlights.put(position, currentHighlights[currentHighlights.length - 1]);
                    }
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }
            }

            @Override
            protected void done() {
                try {
                    positionsIndex = 0;
                    if (!isCancelled()) {
                        fileText.getHighlighter().removeHighlight(highlights.get(positions.get(positionsIndex)));
                        fileText.getHighlighter().addHighlight(positions.get(positionsIndex), positions.get(positionsIndex) + lastRequest.length(),
                                orangeHighlightPainter);
                        highlights.put(positions.get(positionsIndex), fileText.getHighlighter().getHighlights()[fileText.getHighlighter().getHighlights().length - 1]);
                        fileText.setCaretPosition(positions.get(positionsIndex));
                    }
                    progressBar.setIndeterminate(false);
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(TITLE);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            LogSearch search = new LogSearch();
            frame.setContentPane(search.getGUI());
            frame.setSize(new Dimension(600, 600));
            frame.setVisible(true);
        });
    }


    class FileTreeCellRenderer extends DefaultTreeCellRenderer {

        private FileSystemView fileSystemView;

        private JLabel label;

        FileTreeCellRenderer() {
            label = new JLabel();
            label.setOpaque(true);
            fileSystemView = FileSystemView.getFileSystemView();
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus) {

            DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
            File file = (File)node.getUserObject();
            label.setIcon(fileSystemView.getSystemIcon(file));
            label.setText(fileSystemView.getSystemDisplayName(file));
            if (selected) {
                label.setBackground(backgroundSelectionColor);
                label.setForeground(textSelectionColor);
            } else {
                label.setBackground(backgroundNonSelectionColor);
                label.setForeground(textNonSelectionColor);
            }

            return label;
        }
    }
}
