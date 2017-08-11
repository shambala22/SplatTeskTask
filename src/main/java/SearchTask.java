import org.apache.commons.io.FilenameUtils;

import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.RecursiveTask;

/**
 * Created by shambala on 11.08.17.
 */
public class SearchTask extends RecursiveTask<Optional<DefaultMutableTreeNode>> {
    private File file;
    private String searchRequest;
    private String extension;
    private FileSystemView fileSystemView;

    SearchTask(File file, String searchRequest, String extension, FileSystemView fileSystemView) {
        super();
        this.file = file;
        this.searchRequest = searchRequest;
        this.extension = extension;
        this.fileSystemView = fileSystemView;
    }
    @Override
    protected Optional<DefaultMutableTreeNode> compute() {
        DefaultMutableTreeNode currentNode = new DefaultMutableTreeNode(file);
        List<RecursiveTask<Optional<DefaultMutableTreeNode>>> forks = new LinkedList<>();
        if (file.isDirectory()) {
            File[] files = fileSystemView.getFiles(file, true);
            for (File file : files) {
                SearchTask task = new SearchTask(file, searchRequest, extension, fileSystemView);
                forks.add(task);
                task.fork();
            }
            for (RecursiveTask<Optional<DefaultMutableTreeNode>> task : forks) {
                task.join().ifPresent(currentNode::add);
            }
        } else {
            if (FilenameUtils.getExtension(file.getAbsolutePath()).equals(extension)) {
                if (searchInFile(file)) {
                    return Optional.of(currentNode);
                }
            }
        }
        if (currentNode.isLeaf()) {
            return Optional.empty();
        } else return Optional.of(currentNode);
    }

    private boolean searchInFile(File file) {
        try {
            Scanner fileScanner = new Scanner(file);
            while (fileScanner.hasNext()) {
                if (fileScanner.findInLine(searchRequest) != null) {
                    fileScanner.close();
                    return true;
                }
                fileScanner.next();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }
}
