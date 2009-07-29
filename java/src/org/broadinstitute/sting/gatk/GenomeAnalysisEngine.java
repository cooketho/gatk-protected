/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk;

import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.picard.sam.SamFileHeaderMerger;
import net.sf.picard.filter.SamRecordFilter;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMReadGroupRecord;

import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.SAMDataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.datasources.shards.ShardStrategy;
import org.broadinstitute.sting.gatk.datasources.shards.ShardStrategyFactory;
import org.broadinstitute.sting.gatk.executive.MicroScheduler;
import org.broadinstitute.sting.gatk.refdata.ReferenceOrderedData;
import org.broadinstitute.sting.gatk.refdata.ReferenceOrderedDatum;
import org.broadinstitute.sting.gatk.traversals.TraversalEngine;
import org.broadinstitute.sting.gatk.walkers.*;
import org.broadinstitute.sting.gatk.filters.ZeroMappingQualityReadFilter;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.fasta.IndexedFastaSequenceFile;
import org.broadinstitute.sting.utils.cmdLine.ArgumentException;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.lang.reflect.Field;

public class GenomeAnalysisEngine {

    // our instance of this genome analysis toolkit; it's used by other classes to extract the traversal engine
    // TODO: public static without final tends to indicate we're thinking about this the wrong way
    public static GenomeAnalysisEngine instance;

    // our traversal engine
    private TraversalEngine engine = null;
    private SAMDataSource dataSource = null;

    // our argument collection
    private GATKArgumentCollection argCollection;

    /** Collection of output streams used by the walker. */
    private OutputTracker outputTracker = new OutputTracker();

    /** our log, which we want to capture anything from this class */
    private static Logger logger = Logger.getLogger(GenomeAnalysisEngine.class);

    /** our walker manager */
    private final WalkerManager walkerManager;

    /**
     * our constructor, where all the work is done
     * <p/>
     * legacy traversal types are sent to legacyTraversal function; as we move more of the traversals to the
     * new MicroScheduler class we'll be able to delete that function.
     *
     */
    public GenomeAnalysisEngine() {
        // make sure our instance variable points to this analysis engine
        instance = this;
        walkerManager = new WalkerManager();
    }

    /**
     * Actually run the GATK with the specified walker.
     * @param args      the argument collection, where we get all our setup information from
     * @param my_walker Walker to run over the dataset.  Must not be null.
     */
    public Object execute(GATKArgumentCollection args, Walker<?,?> my_walker) {
        // validate our parameters
        if (args == null) {
            throw new StingException("The GATKArgumentCollection passed to GenomeAnalysisEngine can be null.");
        }

        // validate our parameters
        if (my_walker == null)
            throw new StingException("The walker passed to GenomeAnalysisEngine can be null.");

        // save our argument parameter
        this.argCollection = args;

        // our reference ordered data collection
        List<ReferenceOrderedData<? extends ReferenceOrderedDatum>> rods = new ArrayList<ReferenceOrderedData<? extends ReferenceOrderedDatum>>();

        //
        // please don't use these in the future, use the new syntax <- if we're not using these please remove them
        //
        if (argCollection.DBSNPFile != null) bindConvenienceRods("dbSNP", "dbsnp", argCollection.DBSNPFile);
        if (argCollection.HAPMAPFile != null)
            bindConvenienceRods("hapmap", "HapMapAlleleFrequencies", argCollection.HAPMAPFile);
        if (argCollection.HAPMAPChipFile != null)
            bindConvenienceRods("hapmap-chip", "GFF", argCollection.HAPMAPChipFile);
        // TODO: The ROD iterator currently does not understand multiple intervals file.  Fix this by cleaning the ROD system.
        if (argCollection.intervals != null && argCollection.intervals.size() == 1) {
            bindConvenienceRods("interval", "Intervals", argCollection.intervals.get(0).replaceAll(",", ""));
        }

        // parse out the rod bindings
        ReferenceOrderedData.parseBindings(logger, argCollection.RODBindings, rods);

        // Validate the walker inputs against the walker.
        validateInputsAgainstWalker(my_walker, argCollection, rods);

        // our microscheduler, which is in charge of running everything
        MicroScheduler microScheduler = createMicroscheduler(my_walker, rods);

        // create the output streams
        initializeOutputStreams(my_walker);

        // Prepare the sort ordering w.r.t. the sequence dictionary
        if (argCollection.referenceFile != null) {
            final ReferenceSequenceFile refFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(argCollection.referenceFile);
            GenomeLocParser.setupRefContigOrdering(refFile);
        }

        logger.info("Strictness is " + argCollection.strictnessLevel);

        // perform validation steps that are common to all the engines
        engine.setMaximumIterations(argCollection.maximumEngineIterations);
        engine.initialize();

        GenomeLocSortedSet locs = null;
        if (argCollection.intervals != null) {
            locs = GenomeLocSortedSet.createSetFromList(parseIntervalRegion(argCollection.intervals));
        }

        ShardStrategy shardStrategy = this.getShardStrategy(my_walker, microScheduler.getReference(), locs, argCollection.maximumEngineIterations);

        // execute the microscheduler, storing the results
        return microScheduler.execute(my_walker, shardStrategy);
    }

    /**
     * Add additional, externally managed IO streams for walker consumption.
     * @param walkerField Field in the walker into which to inject the value.
     * @param value Instance to inject.
     */
    public void setAdditionalIO( Field walkerField, Object value ) {
        outputTracker.addAdditionalOutput( walkerField, value );    
    }

    /**
     * Gets a set of the names of all walkers that the GATK has discovered.
     * @return A set of the names of all discovered walkers.
     */
    public Set<String> getWalkerNames() {
        return walkerManager.getWalkerNames();
    }

    /**
     * Retrieves an instance of the walker based on the walker name.
     * @param walkerName Name of the walker.  Must not be null.  If the walker cannot be instantiated, an exception will be thrown.
     * @return An instance of the walker.
     */
    public Walker<?,?> getWalkerByName( String walkerName ) {
        return walkerManager.createWalkerByName(walkerName);
    }


    /**
     * setup a microscheduler
     *
     * @param my_walker our walker of type LocusWalker
     * @param rods      the reference order data
     *
     * @return a new microscheduler
     */
    private MicroScheduler createMicroscheduler(Walker my_walker, List<ReferenceOrderedData<? extends ReferenceOrderedDatum>> rods) {
        // the mircoscheduler to return
        MicroScheduler microScheduler = null;

        SAMDataSource readsDataSource = createReadsDataSource(extractSourceInfo(my_walker,argCollection));
        IndexedFastaSequenceFile referenceDataSource = openReferenceSequenceFile(argCollection.referenceFile);
        List<ReferenceOrderedDataSource> rodDataSources = getReferenceOrderedDataSources(rods);

        GenomeLocSortedSet locs = null;
        if (argCollection.intervals != null) {
            locs = GenomeLocSortedSet.createSetFromList(parseIntervalRegion(argCollection.intervals));
        }

        // we need to verify different parameter based on the walker type
        if (my_walker instanceof LocusWalker || my_walker instanceof LocusWindowWalker) {
            // create the MicroScheduler
            microScheduler = MicroScheduler.create(my_walker, readsDataSource, referenceDataSource, rodDataSources, argCollection.numberOfThreads);
        } else if (my_walker instanceof ReadWalker || my_walker instanceof DuplicateWalker) {
            if (argCollection.referenceFile == null)
                Utils.scareUser(String.format("Read-based traversals require a reference file but none was given"));
            microScheduler = MicroScheduler.create(my_walker, readsDataSource, referenceDataSource, rodDataSources, argCollection.numberOfThreads);
        } else {
            Utils.scareUser(String.format("Unable to create the appropriate TraversalEngine for analysis type " + argCollection.analysisName));
        }

        dataSource = microScheduler.getSAMDataSource();
        engine = microScheduler.getTraversalEngine();
        
        return microScheduler;
    }

    /**
     * setup the interval regions, from either the interval file of the genome region string
     *
     * @param intervals the list of intervals to parse
     *
     * @return a list of genomeLoc representing the interval file
     */
    public static List<GenomeLoc> parseIntervalRegion(final List<String> intervals) {
        List<GenomeLoc> locs = new ArrayList<GenomeLoc>();
        for (String interval : intervals) {
            if (new File(interval).exists()) {
                locs.addAll(GenomeLocParser.intervalFileToList(interval));
            } else {
                locs.addAll(GenomeLocParser.parseGenomeLocs(interval));
            }

        }
        return locs;
    }

	/**
	 * Returns sets of samples present in the (merged) input SAM stream, grouped by readers (i.e. underlying
	 * individual bam files). For instance: if GATK is run with three input bam files (three -I arguments), then the list
	 * returned by this method will contain 3 elements (one for each reader), with each element being a set of sample names
	 * found in the corresponding bam file.
	 * @return
	 */
	public List< Set<String> > getSamplesByReaders() {
		
		
		SamFileHeaderMerger hm = getDataSource().getHeaderMerger(); 
		
		List< Set<String> > sample_sets = new ArrayList<Set<String>>(hm.getReaders().size()); 
		
		for ( SAMFileReader r : hm.getReaders() ) {
			
			Set<String> samples = new HashSet<String>(1);
			sample_sets.add(samples);
			
			for ( SAMReadGroupRecord g : r.getFileHeader().getReadGroups() ) {
				samples.add(g.getSample());
			}
		}
		
		return sample_sets;
		
	}

	/**
	 * Returns sets of libraries present in the (merged) input SAM stream, grouped by readers (i.e. underlying
	 * individual bam files). For instance: if GATK is run with three input bam files (three -I arguments), then the list
	 * returned by this method will contain 3 elements (one for each reader), with each element being a set of library names
	 * found in the corresponding bam file.
	 * @return
	 */
	public List< Set<String> > getLibrariesByReaders() {
		
		
		SamFileHeaderMerger hm = getDataSource().getHeaderMerger(); 
		
		List< Set<String> > lib_sets = new ArrayList<Set<String>>(hm.getReaders().size()); 
		
		for ( SAMFileReader r : hm.getReaders() ) {
			
			Set<String> libs = new HashSet<String>(2);
			lib_sets.add(libs);
			
			for ( SAMReadGroupRecord g : r.getFileHeader().getReadGroups() ) {
				libs.add(g.getLibrary());
			}
		}
		
		return lib_sets;
		
	}

	/**
	 * Returns sets of (remapped) read groups in input SAM stream, grouped by readers (i.e. underlying
	 * individual bam files). For instance: if GATK is run with three input bam files (three -I arguments), then the list
	 * returned by this method will contain 3 elements (one for each reader), with each element being a set of remapped read groups
	 * (i.e. as seen by read.getReadGroup().getReadGroupId() in the merged stream) that come from the corresponding bam file.
	 * @return
	 */
	public List< Set<String> > getMergedReadGroupsByReaders() {
		
		
		SamFileHeaderMerger hm = getDataSource().getHeaderMerger(); 
		
		List< Set<String> > rg_sets = new ArrayList<Set<String>>(hm.getReaders().size()); 
		
		for ( SAMFileReader r : hm.getReaders() ) {
			
			Set<String> groups = new HashSet<String>(5);
			rg_sets.add(groups);
			
			for ( SAMReadGroupRecord g : r.getFileHeader().getReadGroups() ) {
				// use HeaderMerger to translate original read group id from the reader into the read group id in the 
				// merged stream, and save that remapped read group id to associate it with specific reader
				groups.add( hm.getReadGroupId(r, g.getReadGroupId()) );
			}
		}
		
		return rg_sets;
		
	}
    
    /**
     * Bundles all the source information about the reads into a unified data structure.
     *
     * @param walker The walker for which to extract info.
     * @param argCollection The collection of arguments passed to the engine.
     *
     * @return The reads object providing reads source info.
     */
    private Reads extractSourceInfo( Walker walker, GATKArgumentCollection argCollection ) {
        List<SamRecordFilter> filters = new ArrayList<SamRecordFilter>();

        filters.addAll( WalkerManager.getReadFilters(walker) );
        if( argCollection.filterZeroMappingQualityReads != null && argCollection.filterZeroMappingQualityReads )
            filters.add( new ZeroMappingQualityReadFilter() );

        return new Reads( argCollection.samFiles,
                          argCollection.strictnessLevel,
                          argCollection.downsampleFraction,
                          argCollection.downsampleCoverage,
                          !argCollection.unsafe,
                          filters );
    }

    private void validateInputsAgainstWalker(Walker walker,
                                             GATKArgumentCollection arguments,
                                             List<ReferenceOrderedData<? extends ReferenceOrderedDatum>> rods) {
        String walkerName = WalkerManager.getWalkerName(walker.getClass());

        // Check what the walker says is required against what was provided on the command line.
        if (WalkerManager.isRequired(walker, DataSource.READS) && (arguments.samFiles == null || arguments.samFiles.size() == 0))
            throw new ArgumentException(String.format("Walker %s requires reads but none were provided.  If this is incorrect, alter the walker's @Requires annotation.", walkerName));
        if (WalkerManager.isRequired(walker, DataSource.REFERENCE) && arguments.referenceFile == null)
            throw new ArgumentException(String.format("Walker %s requires a reference but none was provided.  If this is incorrect, alter the walker's @Requires annotation.", walkerName));

        // Check what the walker says is allowed against what was provided on the command line.
        if ((arguments.samFiles != null && arguments.samFiles.size() > 0) && !WalkerManager.isAllowed(walker, DataSource.READS))
            throw new ArgumentException(String.format("Walker %s does not allow reads but reads were provided.  If this is incorrect, alter the walker's @Allows annotation", walkerName));
        if (arguments.referenceFile != null && !WalkerManager.isAllowed(walker, DataSource.REFERENCE))
            throw new ArgumentException(String.format("Walker %s does not allow a reference but one was provided.  If this is incorrect, alter the walker's @Allows annotation", walkerName));

        // Check to make sure that all required metadata is present.
        List<RMD> allRequired = WalkerManager.getRequiredMetaData(walker);
        for (RMD required : allRequired) {
            boolean found = false;
            for (ReferenceOrderedData<? extends ReferenceOrderedDatum> rod : rods) {
                if (rod.matches(required.name(), required.type()))
                    found = true;
            }
            if (!found)
                throw new ArgumentException(String.format("Unable to find reference metadata (%s,%s)", required.name(), required.type()));
        }

        // Check to see that no forbidden rods are present.
        for (ReferenceOrderedData<? extends ReferenceOrderedDatum> rod : rods) {
            if (!WalkerManager.isAllowed(walker, rod))
                throw new ArgumentException(String.format("Walker does not allow access to metadata: %s.  If this is correct, change the @Allows metadata", rod.getName()));
        }
    }

    /**
     * Convenience function that binds RODs using the old-style command line parser to the new style list for
     * a uniform processing.
     *
     * @param name the name of the rod
     * @param type its type
     * @param file the file to load the rod from
     */
    private void bindConvenienceRods(final String name, final String type, final String file) {
        argCollection.RODBindings.add(Utils.join(",", new String[]{name, type, file}));
    }

    /**
     * Get the sharding strategy given a driving data source.
     *
     * @param walker            Walker for which to infer sharding strategy.
     * @param drivingDataSource Data on which to shard.
     * @param intervals         Intervals to use when limiting sharding.
     * @param maxIterations     the maximum number of iterations to run through
     *
     * @return Sharding strategy for this driving data source.
     */
    protected ShardStrategy getShardStrategy(Walker walker,
                                             ReferenceSequenceFile drivingDataSource,
                                             GenomeLocSortedSet intervals,
                                             Integer maxIterations) {
        final long SHARD_SIZE = 100000L;

        ShardStrategy shardStrategy = null;
        ShardStrategyFactory.SHATTER_STRATEGY shardType;
        if (walker instanceof LocusWalker) {
            if (intervals != null) {
                shardType = (walker.isReduceByInterval()) ?
                        ShardStrategyFactory.SHATTER_STRATEGY.INTERVAL :
                        ShardStrategyFactory.SHATTER_STRATEGY.LINEAR;

                shardStrategy = ShardStrategyFactory.shatter(shardType,
                        drivingDataSource.getSequenceDictionary(),
                        SHARD_SIZE,
                        intervals, maxIterations);
            } else
                shardStrategy = ShardStrategyFactory.shatter(ShardStrategyFactory.SHATTER_STRATEGY.LINEAR,
                        drivingDataSource.getSequenceDictionary(),
                        SHARD_SIZE, maxIterations);

        } else if (walker instanceof ReadWalker ||
                walker instanceof DuplicateWalker) {

            shardType = ShardStrategyFactory.SHATTER_STRATEGY.READS;

            if (intervals != null) {
                shardStrategy = ShardStrategyFactory.shatter(shardType,
                        drivingDataSource.getSequenceDictionary(),
                        SHARD_SIZE,
                        intervals, maxIterations);
            } else {
                shardStrategy = ShardStrategyFactory.shatter(shardType,
                        drivingDataSource.getSequenceDictionary(),
                        SHARD_SIZE, maxIterations);
            }
        } else if (walker instanceof LocusWindowWalker) {
            if( intervals == null )
                throw new StingException("Unable to shard: walker is of type LocusWindow, but no intervals were provided");
            shardStrategy = ShardStrategyFactory.shatter(ShardStrategyFactory.SHATTER_STRATEGY.INTERVAL,
                    drivingDataSource.getSequenceDictionary(),
                    SHARD_SIZE,
                    intervals, maxIterations);
        } else
            throw new StingException("Unable to support walker of type" + walker.getClass().getName());

        return shardStrategy;
    }

    /**
     * Gets a data source for the given set of reads.
     *
     * @param reads   the read source information
     *
     * @return A data source for the given set of reads.
     */
    private SAMDataSource createReadsDataSource(Reads reads) {
        // By reference traversals are happy with no reads.  Make sure that case is handled.
        if (reads.getReadsFiles().size() == 0)
            return null;

        SAMDataSource dataSource = new SAMDataSource(reads);

        return dataSource;
    }

    /**
     * Opens a reference sequence file paired with an index.
     *
     * @param refFile Handle to a reference sequence file.  Non-null.
     *
     * @return A thread-safe file wrapper.
     */
    private IndexedFastaSequenceFile openReferenceSequenceFile(File refFile) {
        IndexedFastaSequenceFile ref = null;
        try {
            ref = new IndexedFastaSequenceFile(refFile);
        }
        catch (FileNotFoundException ex) {
            throw new StingException("I/O error while opening fasta file: " + ex.getMessage(), ex);
        }
        GenomeLocParser.setupRefContigOrdering(ref);
        return ref;
    }

    /**
     * Open the reference-ordered data sources.
     *
     * @param rods the reference order data to execute using
     *
     * @return A list of reference-ordered data sources.
     */
    private List<ReferenceOrderedDataSource> getReferenceOrderedDataSources(List<ReferenceOrderedData<? extends ReferenceOrderedDatum>> rods) {
        List<ReferenceOrderedDataSource> dataSources = new ArrayList<ReferenceOrderedDataSource>();
        for (ReferenceOrderedData<? extends ReferenceOrderedDatum> rod : rods)
            dataSources.add(new ReferenceOrderedDataSource(rod));
        return dataSources;
    }

    /**
     * Initialize the output streams as specified by the user.
     *
     * @param walker the walker to initialize output streams for
     */
    private void initializeOutputStreams(Walker walker) {
        if( argCollection.outErrFileName != null )
            outputTracker.initializeCoreIO( argCollection.outErrFileName, argCollection.outErrFileName );
        else
            outputTracker.initializeCoreIO( argCollection.outFileName, argCollection.errFileName );
        outputTracker.prepareWalker(walker);
    }

    /**
     * Gets the output tracker.  Tracks data available to a given walker.
     *
     * @return The output tracker.
     */
    public OutputTracker getOutputTracker() {
        return outputTracker;
    }

    public TraversalEngine getEngine() {
        return this.engine;
    }

    /**
     * Returns data source object encapsulating all essential info and handlers used to traverse
     * reads; header merger, individual file readers etc can be accessed through the returned data source object.
     * @return
     */
    public SAMDataSource getDataSource() {
        return this.dataSource;
    }

    /**
     * Gets the collection of GATK main application arguments for enhanced walker validation.
     *
     * @return the GATK argument collection
     */
    public GATKArgumentCollection getArguments() {
        return this.argCollection;
    }
}
