package org.asciidoctor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * 
 * Directory walker that finds all files that match the given glob expression.
 * 
 * Code is based on a class of wildcard project
 * (https://code.google.com/p/wildcard/).
 * 
 * @author lordofthejars
 * 
 */
public class GlobDirectoryWalker implements DirectoryWalker {

    private final File rootDirectory;
    private final File canonicalRootDir;
    private final List<File> matches = new ArrayList<File>();
    private final String globExpression;

    public GlobDirectoryWalker(String globExpression) {

        if (isAbsoluteGlobExpression(globExpression)) {

            // If the given path is an absolute path we want to split it into an absolute part containing
            // directories without wildcards and a glob part with wildcards and file names
            int indexOfUnglobbedPart = findIndexOfUnglobbedPart(globExpression);
            this.rootDirectory = new File(globExpression.substring(0, indexOfUnglobbedPart));
            this.globExpression = globExpression.substring(indexOfUnglobbedPart + 1);
            checkInput(rootDirectory);
            this.canonicalRootDir = getCanonicalPath(rootDirectory);

        } else {
            // It's a relative expression, current working dir is sufficient as root directory
            rootDirectory = new File(".");
            checkInput(rootDirectory);
            this.canonicalRootDir = getCanonicalPath(rootDirectory);
            this.globExpression = globExpression;

        }
    }

    // An absolute path will consist of an absolute, unglobbed part and a globbed part.
    // The globbed part starts at the first path element that contains a '*' or it is
    // the part following the last separator
    private int findIndexOfUnglobbedPart(String globExpression) {
        int result = 0;
        for (int i = 0; i < globExpression.length(); i++) {
            switch (globExpression.charAt(i)) {
                case '/':
                case '\\':
                    result = i;
                    break;
                case '*':
                    return result;
                default:
            }
        }
        // There is apparently no wildcard in the path, let the directory part be the path
        // and the glob part only the filename
        return result;
    }

    // Determines if the given glob expression is an absolute path
    // That is the expression starts with a separator or with [A-Za-z]:[/\] on Windows
    private boolean isAbsoluteGlobExpression(String globExpression) {
        if (globExpression.startsWith(File.separator)) {
            return true;
        }
        if (isWindows()) {
            return globExpression.matches("^[A-Za-z]:[\\\\/].*$");
        }
        return false;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @Override
    public List<File> scan() {
        Pattern pattern = new Pattern(globExpression);

        scanDir(this.canonicalRootDir, Arrays.asList(pattern));
        
        return this.matches;
    }

    private void checkInput(File rootDir) {
        if (!rootDir.exists()) {
            throw new IllegalArgumentException("Directory does not exist: "
                    + rootDir);
        }

        if (!rootDir.isDirectory()) {
            throw new IllegalArgumentException("File must be a directory: "
                    + rootDir);
        }
    }

    private File getCanonicalPath(File rootDir) {
        try {
            rootDir = rootDir.getCanonicalFile();
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "Error determining canonical path: " + rootDir, ex);
        }
        return rootDir;
    }
    
    private void scanDir(File dir, List<Pattern> includes) {

        if (!dir.canRead())
            return;

        if (isGlobalExpression(includes)) {
            findFilesThroughMatchingDirectories(dir, includes);
        } else {
            findFileInSpecificLocation(dir, includes);
        }

    }

    private void findFileInSpecificLocation(File dir, List<Pattern> includes) {
        List<Pattern> matchingIncludes = new ArrayList<Pattern>(1);
        for (Pattern include : includes) {
            if (matchingIncludes.isEmpty()) {
                matchingIncludes.add(include);
            } else {
                matchingIncludes.set(0, include);
            }
            process(dir, include.value, matchingIncludes);
        }
    }

    private void findFilesThroughMatchingDirectories(File dir,
            List<Pattern> includes) {
        for (String fileName : dir.list()) {
            List<Pattern> matchingIncludes = new ArrayList<Pattern>(
                    includes.size());
            for (Pattern include : includes) {
                if (include.matches(fileName)) {
                    matchingIncludes.add(include);
                }
            }
            if (matchingIncludes.isEmpty()) {
                continue;
            }
            process(dir, fileName, matchingIncludes);
        }
    }

    private void process(File dir, String fileName,
            List<Pattern> matchingIncludes) {
        // Increment patterns that need to move to the next token.
        boolean isFinalMatch = false;
        
        List<Pattern> incrementedPatterns = new ArrayList<Pattern>();
        for (Iterator<Pattern> iter = matchingIncludes.iterator(); iter
                .hasNext();) {
            Pattern include = iter.next();
            if (include.incr(fileName)) {
                incrementedPatterns.add(include);
                if (include.isExhausted()) {
                    iter.remove();
                }
            }
            if (include.wasFinalMatch()) {
                isFinalMatch = true;
            }
        }

        File file = new File(dir, fileName);
        if (isFinalMatch) {
            int length = canonicalRootDir.getPath().length();
            if (!canonicalRootDir.getPath().endsWith(File.separator)) {
                length++; // Lose starting slash.
            }
            matches.add(new File(this.rootDirectory, file.getPath().substring(length)));
        }
        
        if (!matchingIncludes.isEmpty() && file.isDirectory()) {
            scanDir(file, matchingIncludes);
        }

        // Decrement patterns.
        for (Pattern include : incrementedPatterns) {
            include.decr();
        }
    }
    
    private boolean isGlobalExpression(List<Pattern> includes) {
        boolean scanAll = false;
        for (Pattern include : includes) {
            if (include.value.indexOf('*') != -1
                    || include.value.indexOf('?') != -1) {
                scanAll = true;
                break;
            }
        }
        return scanAll;
    }

    static class Pattern {
        String value;
        final String[] values;

        private int index;

        Pattern(String pattern) {
            pattern = pattern.replace('\\', '/');
            pattern = pattern.replaceAll("\\*\\*[^/]", "**/*");
            pattern = pattern.replaceAll("[^/]\\*\\*", "*/**");

            values = pattern.split("/");
            value = values[0];
        }

        boolean matches(String fileName) {
            if (value.equals("**"))
                return true;

            // Shortcut if no wildcards.
            if (value.indexOf('*') == -1 && value.indexOf('?') == -1) {
                return fileName.equals(value);
            }

            int i = 0, j = 0;
            while (i < fileName.length() && j < value.length()
                    && value.charAt(j) != '*') {
                if (value.charAt(j) != fileName.charAt(i)
                        && value.charAt(j) != '?') {
                    return false;
                }
                i++;
                j++;
            }

            // If reached end of pattern without finding a * wildcard, the match
            // has to fail if not same length.
            if (j == value.length()) {
                return fileName.length() == value.length();
            }

            int cp = 0;
            int mp = 0;
            while (i < fileName.length()) {
                if (j < value.length() && value.charAt(j) == '*') {
                    if (j++ >= value.length())
                        return true;
                    mp = j;
                    cp = i + 1;
                } else if (j < value.length()
                        && (value.charAt(j) == fileName.charAt(i) || value
                                .charAt(j) == '?')) {
                    j++;
                    i++;
                } else {
                    j = mp;
                    i = cp++;
                }
            }

            // Handle trailing asterisks.
            while (j < value.length() && value.charAt(j) == '*') {
                j++;
            }

            return j >= value.length();
        }

        String nextValue() {
            if (index + 1 == values.length) {
                return null;
            }

            return values[index + 1];
        }

        boolean incr(String fileName) {
            if (value.equals("**")) {
                if (index == values.length - 1) {
                    return false;
                }
                incr();
                if (matches(fileName)) {
                    incr();
                } else {
                    decr();
                    return false;
                }
            } else {
                incr();
            }
            return true;
        }

        void incr() {
            index++;
            if (index >= values.length) {
                value = null;
            } else {
                value = values[index];
            }
        }

        void decr() {
            index--;
            if (index > 0 && values[index - 1].equals("**")) {
                index--;
            }
            value = values[index];
        }

        void reset() {
            index = 0;
            value = values[0];
        }

        boolean isExhausted() {
            return index >= values.length;
        }

        boolean isLast() {
            return index >= values.length - 1;
        }

        boolean wasFinalMatch() {
            return isExhausted() || (isLast() && value.equals("**"));
        }
    }

}
