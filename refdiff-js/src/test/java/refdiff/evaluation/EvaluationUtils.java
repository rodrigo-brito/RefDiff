package refdiff.evaluation;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import refdiff.core.diff.RastComparator;
import refdiff.core.diff.RastDiff;
import refdiff.core.diff.Relationship;
import refdiff.core.diff.RelationshipType;
import refdiff.core.diff.similarity.TfIdfSourceRepresentation;
import refdiff.core.diff.similarity.TfIdfSourceRepresentationBuilder;
import refdiff.core.io.FileSystemSourceFile;
import refdiff.core.io.GitHelper;
import refdiff.core.io.SourceFile;
import refdiff.parsers.java.JavaParser;
import refdiff.parsers.java.JavaSourceTokenizer;
import refdiff.parsers.java.NodeTypes;

public class EvaluationUtils {
	
	private JavaParser parser = new JavaParser();
	private JavaSourceTokenizer tokenizer = new JavaSourceTokenizer();
	private RastComparator<TfIdfSourceRepresentation> comparator = new RastComparator<>(parser, tokenizer, new TfIdfSourceRepresentationBuilder());
	private final String tempFolder = "D:/tmp/";
	
	public RefactoringSet runRefDiff(String project, String commit) throws Exception {
		RefactoringSet rs = new RefactoringSet(project, commit);
		
		String repoFolder = repoFolder(project);
		String checkoutFolderV0 = checkoutFolder(tempFolder, project, commit, "v0");
		String checkoutFolderV1 = checkoutFolder(tempFolder, project, commit, "v1");
		
		prepareSourceCode(project, commit);
		
		GitHelper gitHelper = new GitHelper();
		try (
			Repository repo = gitHelper.openRepository(tempFolder + repoFolder);
			RevWalk rw = new RevWalk(repo)) {
			
			RevCommit revCommit = rw.parseCommit(repo.resolve(commit));
			rw.parseCommit(revCommit.getParent(0));
			
			List<String> filesV0 = new ArrayList<>();
			List<String> filesV1 = new ArrayList<>();
			Map<String, String> renamedFilesHint = new HashMap<>();
			
			gitHelper.fileTreeDiff(repo, revCommit, filesV0, filesV1, renamedFilesHint, false);
			
			System.out.println(String.format("Computing diff for %s %s", project, commit));
			RastDiff diff = comparator.compare(getSourceFiles(checkoutFolderV0, filesV0), getSourceFiles(checkoutFolderV1, filesV1));
			
			for (Relationship rel : diff.getRelationships()) {
				RelationshipType relType = rel.getType();
				String nodeType = rel.getNodeAfter().getType();
				Optional<RefactoringType> refType = getRefactoringType(relType, nodeType);
				if (refType.isPresent()) {
					String keyN1 = JavaParser.getKey(rel.getNodeBefore());
					String keyN2 = JavaParser.getKey(rel.getNodeAfter());
					rs.add(refType.get(), keyN1, keyN2, rel.getSimilarity());
				}
			}
		}
		
		return rs;
	}

	private String checkoutFolder(String tempFolder, String project, String commit, String prefixFolder) {
		return tempFolder + prefixFolder + "/" + repoFolder(project) + "-" + commit.substring(0, 7) + "/";
	}

	private String repoFolder(String project) {
		return project.substring(project.lastIndexOf('/') + 1, project.lastIndexOf('.'));
	}

	public void prepareSourceCode(String project, String commit) {
		String repoFolder = repoFolder(project);
		String checkoutFolderV0 = checkoutFolder(tempFolder, project, commit, "v0");
		String checkoutFolderV1 = checkoutFolder(tempFolder, project, commit, "v1");
		try {
			File fRepoFolder = new File(tempFolder + repoFolder);
			if (!fRepoFolder.exists()) {
				ExternalProcess.execute(new File(tempFolder), "git", "clone", "--bare", "--shallow-since=2015-05-01");
//				fRepoFolder.mkdirs();
//				ExternalProcess.execute(fRepoFolder, "git", "init", "--bare");
//				ExternalProcess.execute(fRepoFolder, "git", "remote", "add", "origin", project);
			} else {
				ExternalProcess.execute(fRepoFolder, "git", "fetch", "--all", "--shallow-since=2015-05-01");
			}
			File fCheckoutFolderV0 = new File(checkoutFolderV0);
			File fCheckoutFolderV1 = new File(checkoutFolderV1);
			if (!fCheckoutFolderV0.exists() || !fCheckoutFolderV1.exists()) {
//				ExternalProcess.execute(fRepoFolder, "git", "fetch", "--depth", "2", "origin", commit);
				
				if (!fCheckoutFolderV0.exists() && fCheckoutFolderV0.mkdirs()) {
					ExternalProcess.execute(fRepoFolder, "git", "--work-tree=" + checkoutFolderV0, "checkout", commit + "~", "--", ".");
				}
				if (!fCheckoutFolderV1.exists() && fCheckoutFolderV1.mkdirs()) {
					ExternalProcess.execute(fRepoFolder, "git", "--work-tree=" + checkoutFolderV1, "checkout", commit, "--", ".");
				}
			}
		} catch (RuntimeException e) {
			System.err.println(e.getMessage());
		}
	}
	
	private List<SourceFile> getSourceFiles(String checkoutFolder, List<String> files) {
		List<SourceFile> list = new ArrayList<>();
		for (String file : files) {
			list.add(new FileSystemSourceFile(Paths.get(checkoutFolder), Paths.get(file)));
		}
		return list;
	}
	
	private Optional<RefactoringType> getRefactoringType(RelationshipType relType, String nodeType) {
		boolean isType = nodeType.equals(NodeTypes.CLASS_DECLARATION) || nodeType.equals(NodeTypes.ENUM_DECLARATION) || nodeType.equals(NodeTypes.INTERFACE_DECLARATION);
		boolean isInterface = nodeType.equals(NodeTypes.INTERFACE_DECLARATION);
		boolean isMethod = nodeType.equals(NodeTypes.METHOD_DECLARATION);
		
		switch (relType) {
		case MOVE:
			if (isType) {
				return Optional.of(RefactoringType.MOVE_CLASS);
			} else if (isMethod) {
				return Optional.of(RefactoringType.MOVE_OPERATION);
			}
			break;
		case RENAME:
			if (isType) {
				return Optional.of(RefactoringType.RENAME_CLASS);
			} else if (isMethod) {
				return Optional.of(RefactoringType.RENAME_METHOD);
			}
			break;
		case EXTRACT:
			if (isMethod) {
				return Optional.of(RefactoringType.EXTRACT_OPERATION);
			}
			break;
		case INLINE:
			if (isMethod) {
				return Optional.of(RefactoringType.INLINE_OPERATION);
			}
			break;
		case PULL_UP:
		case PULL_UP_SIGNATURE:
			if (isMethod) {
				return Optional.of(RefactoringType.PULL_UP_OPERATION);
			}
			break;
		case PUSH_DOWN:
		case PUSH_DOWN_IMPL:
			if (isMethod) {
				return Optional.of(RefactoringType.PUSH_DOWN_OPERATION);
			}
			break;
		case EXTRACT_SUPER:
			if (isInterface) {
				return Optional.of(RefactoringType.EXTRACT_INTERFACE);
			} else if (isType) {
				return Optional.of(RefactoringType.EXTRACT_SUPERCLASS);
			}
			break;
		case SAME:
			return Optional.empty();
		}
		return Optional.empty();
		//throw new RuntimeException(String.format("Cannot convert to refactoring: %s %s", relType, nodeType));
	}
}
