package com.inmobi.databus.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * This class checks the data consistency between merged stream and mirrored 
 * stream for different clusters. It returns the both missed paths and data 
 * replay paths.
 * This class main method takes merged stream, comma separated list of mirror 
 * stream urls and comma separated list of stream names
 *
 */
public class MirrorStreamDataConsistencyValidation {
  private static final Log LOG = LogFactory.getLog(
      MirrorStreamDataConsistencyValidation.class);
  List<Path> mirrorStreamDirs;
  Path mergedStreamDirPath;

  public MirrorStreamDataConsistencyValidation(String mirrorStreamUrls, 
      String mergedStreamUrl) throws IOException {
    String[] rootDirSplits;
    mergedStreamDirPath = new Path(mergedStreamUrl, "streams");
    if (mirrorStreamUrls != null) {
      rootDirSplits = mirrorStreamUrls.split(",");
    } else {
      throw new IllegalArgumentException("Databus root directory not specified");
    }
    mirrorStreamDirs = new ArrayList<Path>(rootDirSplits.length);
    for (String mirrorRootDir : rootDirSplits) {
      mirrorStreamDirs.add(new Path(mirrorRootDir, "streams"));
    }
  }

  public List<Path> processListingStreams(String streamName, 
      boolean copyMissingPaths) throws IOException {
    List<Path> inconsistentData = new ArrayList<Path>();
    TreeMap<Path, FileStatus> filesInMergedStream = new TreeMap<Path, FileStatus>();
    mergedStreamListing(streamName, filesInMergedStream);
    mirrorStreamListing(streamName, inconsistentData, filesInMergedStream, 
        copyMissingPaths);
    return inconsistentData;
  }

  public void mergedStreamListing(String streamName, TreeMap<Path, FileStatus>  
  filesInMergedStream) throws IOException {
    Path completeMergedStreamDirPath = new Path(mergedStreamDirPath,
        streamName);
    LOG.info("merged Stream path : " + completeMergedStreamDirPath);    
    FileSystem mergedFS = completeMergedStreamDirPath.getFileSystem(
        new Configuration());
    doRecursiveListing(completeMergedStreamDirPath, filesInMergedStream,
        mergedFS);             
    Iterator<Path> it = filesInMergedStream.keySet().iterator();
    while (it.hasNext()) {
      LOG.debug(" files in merged stream : " + (it.next()));
    }
  }

  public void mirrorStreamListing(String streamName, List<Path> inconsistentData,
      TreeMap<Path, FileStatus> filesInMergedStream, boolean copyMissingPaths)
      throws IOException {
    Path mirrorStreamDirPath;
    FileSystem mirroredFs;
    for (int i = 0; i < mirrorStreamDirs.size(); i++) {
      TreeMap<Path, FileStatus> filesInMirroredStream = new TreeMap<Path, FileStatus>();
      mirrorStreamDirPath = new Path(mirrorStreamDirs.get(i), streamName);
      mirroredFs = mirrorStreamDirPath.getFileSystem(new Configuration());
      LOG.info("mirroredStream Path : " + mirrorStreamDirPath);
      
      doRecursiveListing(mirrorStreamDirPath, filesInMirroredStream, mirroredFs);
      
      Iterator<Path> it = filesInMirroredStream.keySet().iterator();
      while (it.hasNext() ) {
        LOG.debug(" files in mirrored stream: " + (it.next()));
      }
      System.out.println("stream name:" + streamName);
      
      TreeMap<Path, FileStatus> toBeCopiedPaths = new TreeMap<Path, FileStatus>();
      compareMergedAndMirror(filesInMergedStream, filesInMirroredStream, 
          mirrorStreamDirs.get(i).toString(), mergedStreamDirPath.
          toString(), inconsistentData, toBeCopiedPaths);
      
      // check if there are missing paths that need to be copied to mirror stream
      if (toBeCopiedPaths.size() > 0 && copyMissingPaths) {
        it = toBeCopiedPaths.keySet().iterator();
        while (it.hasNext()) {
          LOG.debug(" To be copied path: " + (it.next()));
        }
        
        //TODO: perform distcp copy of missing paths
      }
    }
  }

  /**
   * It compares the merged stream and mirror streams 
   * stores the missed paths and data replay paths in the inconsistent data List
   * @param mergedStreamFiles : list of files in the merged stream
   * @param mirrorStreamFiles : list of files in the mirrored stream
   * @param mirrorStreamDirPath: mirror stream dir path for finding   
   * 				minute dirs paths only
   * @param mergedStreamDirPath : merged stream dir path for finding 
   * 			  minute dirs paths only
   * @param inconsistentData : stores all the missed paths and data replay paths
   */
  void compareMergedAndMirror(TreeMap<Path, FileStatus> mergedStreamFiles, 
      TreeMap<Path, FileStatus> mirrorStreamFiles, String mirrorStreamDirPath, 
      String mergedStreamDirPath, List<Path> inconsistentData,
      TreeMap<Path, FileStatus> toBeCopiedPaths) {
    int i;
    int j;
    int mergedStreamLen = mergedStreamDirPath.length();
    int mirrorStreamLen = mirrorStreamDirPath.length();
    Iterator<Entry<Path, FileStatus>> mergedStreamIter = 
        mergedStreamFiles.entrySet().iterator();
    Iterator<Entry<Path, FileStatus>> mirrorStreamIter = 
        mirrorStreamFiles.entrySet().iterator();
    
    for(i=0, j=0 ; i < mergedStreamFiles.size() && 
        j < mirrorStreamFiles.size(); i++, j++) {
      Entry<Path, FileStatus> mergedStreamEntry = mergedStreamIter.next();
      Entry<Path, FileStatus> mirrorStreamEntry = mirrorStreamIter.next();
      
      Path mergedStreamFilePath = mergedStreamEntry.getKey();
      Path mirrorStreamFilePath = mirrorStreamEntry.getKey();
      
      String mergedStreamFileRelPath = mergedStreamFilePath.toString().
          substring(mergedStreamLen);
      String mirrorStreamFileRelPath = mirrorStreamFilePath.toString().
          substring(mirrorStreamLen);
      
      if(!mergedStreamFileRelPath.equals(mirrorStreamFileRelPath)) {
        if(mergedStreamFileRelPath.compareTo(mirrorStreamFileRelPath) < 0) {
          if (j == 0) {
            System.out.println("purged path in the mirror stream" + 
                mergedStreamFilePath);
          } else {
            System.out.println("Missing file path : " + mergedStreamFilePath);
            
            // Add the entry for missing file in the toBeCopied map
            toBeCopiedPaths.put(new Path(mergedStreamFileRelPath), 
                mergedStreamEntry.getValue());
          }
          inconsistentData.add(mergedStreamFilePath);
          --j;
        } else {
          if (i == 0) {
            System.out.println("purged path in the merged stream" + 
               mirrorStreamFilePath);
          } else {
            System.out.println("Data Replica : " + mirrorStreamFilePath);
          }
          inconsistentData.add(mirrorStreamFilePath);
          --i;
        }
      } else {
        // System.out.println("match between   " + i + " and  " + j);
      }	   
    }	
    if((i == j) && i== mergedStreamFiles.size() && j == mirrorStreamFiles.size()) {
      System.out.println("There are no missing paths");
    } else {
      /* check whether there are any missing file paths or extra dummy files  
       * or not
       */
      if(i == mergedStreamFiles.size() ) {
        for(;j < mirrorStreamFiles.size(); j++) {
          Path mirrorStreamFilePath = mirrorStreamIter.next().getKey();
          System.out.println("Extra files are in the Mirrored Stream: " + 
              mirrorStreamFilePath);	
          inconsistentData.add(mirrorStreamFilePath);
        }
      } else {
        for( ; i < mergedStreamFiles.size(); i++) {
          Path mergedStreamFilePath = mergedStreamIter.next().getKey();
          System.out.println("To be Mirrored files: " + mergedStreamFilePath);	
          inconsistentData.add(mergedStreamFilePath);
        }
      }
    } 
  }

  /**
   * It lists all the dirs and file paths which are presented in 
   * the stream directory
   * @param dir : stream directory path 
   * @param fileListingMap : map with key as dest path and value as source FileStatus
   * @param fs : FileSystem object
   */
  public void doRecursiveListing(Path dir, TreeMap<Path, FileStatus> fileListingMap,
      FileSystem fs) throws IOException {
    FileStatus[] fileStatuses = fs.listStatus(dir);
    if (fileStatuses == null || fileStatuses.length == 0) {
      LOG.debug("No files in directory:" + dir);
      // get the file status for empty directory
      fileListingMap.put(dir, fs.getFileStatus(dir));
    } else {
      for (FileStatus file : fileStatuses) {
        if (file.isDir()) {
          doRecursiveListing(file.getPath(), fileListingMap, fs);
        } else {
          fileListingMap.put(dir, file);
        }
      } 
    }
  }

  public List<Path> run(String [] args) throws Exception {
    List<String> streamNames = new ArrayList<String>();
    List<Path> inconsistentData = new ArrayList<Path>();
    boolean copyMissingPaths = false;
    
    if (args.length == 2) {
      FileSystem fs = mirrorStreamDirs.get(0).getFileSystem(new Configuration());
      FileStatus[] fileStatuses = fs.listStatus(mirrorStreamDirs.get(0));
      if (fileStatuses != null && fileStatuses.length != 0) {
        for (FileStatus file : fileStatuses) {  
          streamNames.add(file.getPath().getName());
        } 
      } else {
        System.out.println("There are no stream names in the mirrored stream");
      }
    } else if (args.length == 3) {
      if (args[2].equalsIgnoreCase("-fix")) {
        copyMissingPaths = true;
      } else {
        // the 3rd argument is a list of stream names
        for (String streamname : args[2].split(",")) {
          streamNames.add(streamname);
        }
      }
    } else if (args.length == 4) {
      // the 4th argument is whether missing paths need to be copied
      if (args[3].equalsIgnoreCase("-fix")) {
        copyMissingPaths = true;
      }
    }
    
    for (String streamName : streamNames) {
      inconsistentData.addAll(this.processListingStreams(streamName, copyMissingPaths));
    }
    if (inconsistentData.isEmpty()) {
      System.out.println("there is no inconsistency data");
    }
    return inconsistentData;
  }

  static void printUsage() {
    System.out.println("Usage: mirrorstreamdataconsistency  "
        + " <mergedstream root-dir>"
        + " <mirrorstream root-dir (comma separated list)>"
        + " [<streamname (comma separated list)>]"
        + " [-fix (needs shutdown of merge and mirror databus workers) ");
  }
  
  public static void main(String args[]) throws Exception {
    if (args.length >= 2) {
      String mergedStreamUrl = args[0];
      String mirrorStreamUrls = args[1];	
      MirrorStreamDataConsistencyValidation obj = new 
          MirrorStreamDataConsistencyValidation(mirrorStreamUrls, 
              mergedStreamUrl);
      obj.run(args);
    } else {
      printUsage();
      System.exit(1);
    }
  }
}
