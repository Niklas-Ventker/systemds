/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.controlprogram.parfor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;

import com.ibm.bi.dml.lops.runtime.RunMRJobs.ExecMode;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.caching.MatrixObject;
import com.ibm.bi.dml.runtime.controlprogram.parfor.util.StagingFileUtils;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.io.InputInfo;
import com.ibm.bi.dml.runtime.matrix.io.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.io.MatrixCell;
import com.ibm.bi.dml.runtime.matrix.io.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.io.OutputInfo;
import com.ibm.bi.dml.runtime.matrix.io.TaggedMatrixBlock;
import com.ibm.bi.dml.runtime.matrix.io.TaggedMatrixCell;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration;
import com.ibm.bi.dml.runtime.util.LocalFileUtils;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.utils.Statistics;

/**
 * MR job class for submitting parfor result merge MR jobs.
 * 
 */
public class ResultMergeRemoteMR extends ResultMerge
{	
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public static final byte COMPARE_TAG = 'c';
	public static final byte DATA_TAG = 'd';
	
	private long _pfid = -1;
	private int  _numMappers = -1;
	private int  _numReducers = -1;
	private int  _replication = -1;
	private int  _max_retry = -1;
	private boolean _jvmReuse = false;

	public ResultMergeRemoteMR(MatrixObject out, MatrixObject[] in, String outputFilename, long pfid, int numMappers, int numReducers, int replication, int max_retry, boolean jvmReuse) 
	{
		super(out, in, outputFilename);
		
		_pfid = pfid;
		_numMappers = numMappers;
		_numReducers = numReducers;
		_replication = replication;
		_max_retry = max_retry;
		_jvmReuse = jvmReuse;
	}

	@Override
	public MatrixObject executeSerialMerge() 
		throws DMLRuntimeException 
	{
		//graceful degradation to parallel merge
		return executeParallelMerge( _numMappers );
	}
	
	@Override
	public MatrixObject executeParallelMerge(int par) 
		throws DMLRuntimeException 
	{
		MatrixObject moNew = null; //always create new matrix object (required for nested parallelism)

		//Timing time = null;
		LOG.trace("ResultMerge (local, file): Execute serial merge for output "+_output.getVarName()+" (fname="+_output.getFileName()+")");
		//	time = new Timing();
		//	time.start();
		
		
		try
		{
			//collect all relevant inputs
			Collection<String> srcFnames = new LinkedList<String>();
			ArrayList<MatrixObject> inMO = new ArrayList<MatrixObject>();
			for( MatrixObject in : _inputs )
			{
				//check for empty inputs (no iterations executed)
				if( in !=null && in != _output ) 
				{
					//ensure that input file resides on disk
					in.exportData();
					
					//add to merge list
					srcFnames.add( in.getFileName() );
					inMO.add(in);
				}
			}

			if( srcFnames.size() > 0 )
			{
				//ensure that outputfile (for comparison) resides on disk
				_output.exportData();
				
				//actual merge
				MatrixFormatMetaData metadata = (MatrixFormatMetaData) _output.getMetaData();
				MatrixCharacteristics mcOld = metadata.getMatrixCharacteristics();
				
				String fnameCompare = _output.getFileName();
				if( mcOld.nonZero==0 )
					fnameCompare = null; //no compare required
				
				executeMerge(fnameCompare, _outputFName, srcFnames.toArray(new String[0]), 
						     metadata.getInputInfo(),metadata.getOutputInfo(), mcOld.get_rows(), mcOld.get_cols(),
						     mcOld.get_rows_per_block(), mcOld.get_cols_per_block());
				
				//create new output matrix (e.g., to prevent potential export<->read file access conflict
				String varName = _output.getVarName();
				ValueType vt = _output.getValueType();
				moNew = new MatrixObject( vt, _outputFName );
				moNew.setVarName( varName.contains(NAME_SUFFIX) ? varName : varName+NAME_SUFFIX );
				moNew.setDataType( DataType.MATRIX );
				OutputInfo oiOld = metadata.getOutputInfo();
				InputInfo iiOld = metadata.getInputInfo();
				MatrixCharacteristics mc = new MatrixCharacteristics(mcOld.get_rows(),mcOld.get_cols(),
						                                             mcOld.get_rows_per_block(),mcOld.get_cols_per_block());
				mc.setNonZeros( computeNonZeros(_output, inMO) );
				MatrixFormatMetaData meta = new MatrixFormatMetaData(mc,oiOld,iiOld);
				moNew.setMetaData( meta );
			}
			else
			{
				moNew = _output; //return old matrix, to prevent copy
			}
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException(ex);
		}

		//LOG.trace("ResultMerge (local, file): Executed serial merge for output "+_output.getVarName()+" (fname="+_output.getFileName()+") in "+time.stop()+"ms");
		
		return moNew;		
	}
	
	/**
	 * 
	 * @param fname 	null if no comparison required
	 * @param fnameNew
	 * @param srcFnames
	 * @param ii
	 * @param oi
	 * @param rlen
	 * @param clen
	 * @param brlen
	 * @param bclen
	 * @throws DMLRuntimeException
	 */
	protected void executeMerge(String fname, String fnameNew, String[] srcFnames, InputInfo ii, OutputInfo oi, long rlen, long clen, int brlen, int bclen)
			throws DMLRuntimeException 
	{
		JobConf job;
		job = new JobConf( ResultMergeRemoteMR.class );
		job.setJobName("ParFor_ResultMerge-MR"+_pfid);

		//maintain dml script counters
		Statistics.incrementNoOfCompiledMRJobs();
		
		//warning for textcell/binarycell without compare
		boolean withCompare = (fname!=null);
		if( (oi == OutputInfo.TextCellOutputInfo || oi == OutputInfo.BinaryCellOutputInfo) && !withCompare && ResultMergeLocalFile.ALLOW_COPY_CELLFILES )
			LOG.warn("Result merge for "+OutputInfo.outputInfoToString(oi)+" without compare can be realized more efficiently with LOCAL_FILE than REMOTE_MR.");
			
		try
		{
			Path pathCompare = null;
			Path pathNew = new Path(fnameNew);
			
			/////
			//configure the MR job
			if( withCompare ) {
				pathCompare = new Path(fname).makeQualified(FileSystem.get(job));
				MRJobConfiguration.setResultMergeInfo(job, pathCompare.toString(), ii, LocalFileUtils.getWorkingDir(LocalFileUtils.CATEGORY_RESULTMERGE), rlen, clen, brlen, bclen);
			}
			else
				MRJobConfiguration.setResultMergeInfo(job, "null", ii, LocalFileUtils.getWorkingDir(LocalFileUtils.CATEGORY_RESULTMERGE), rlen, clen, bclen, bclen);
			
			
			//set mappers, reducers, combiners
			job.setMapperClass(ResultMergeRemoteMapper.class); 
			job.setReducerClass(ResultMergeRemoteReducer.class);
			
			if( oi == OutputInfo.TextCellOutputInfo )
			{
				job.setMapOutputKeyClass(MatrixIndexes.class);
				job.setMapOutputValueClass(TaggedMatrixCell.class);
				job.setOutputKeyClass(NullWritable.class);
				job.setOutputValueClass(Text.class);
			}
			else if( oi == OutputInfo.BinaryCellOutputInfo )
			{
				job.setMapOutputKeyClass(MatrixIndexes.class);
				job.setMapOutputValueClass(TaggedMatrixCell.class);
				job.setOutputKeyClass(MatrixIndexes.class);
				job.setOutputValueClass(MatrixCell.class);
			}
			else if ( oi == OutputInfo.BinaryBlockOutputInfo )
			{
				//setup partitioning, grouping, sorting for composite key (old API)
				job.setPartitionerClass(ResultMergeRemotePartitioning.class); //partitioning
		        job.setOutputValueGroupingComparator(ResultMergeRemoteGrouping.class); //grouping
		        job.setOutputKeyComparatorClass(ResultMergeRemoteSorting.class); //sorting
		        
				job.setMapOutputKeyClass(ResultMergeTaggedMatrixIndexes.class);
				job.setMapOutputValueClass(TaggedMatrixBlock.class);
				job.setOutputKeyClass(MatrixIndexes.class);
				job.setOutputValueClass(MatrixBlock.class);
			}
			
			//set input format 
			job.setInputFormat(ii.inputFormatClass);
			
			//set the input path 
			Path[] paths = null;
			if( withCompare ) {
				paths= new Path[ srcFnames.length+1 ];
				paths[0] = pathCompare;
				for(int i=1; i<paths.length; i++)
					paths[i] = new Path( srcFnames[i-1] ); 
			}
			else {
				paths= new Path[ srcFnames.length ];
				for(int i=0; i<paths.length; i++)
					paths[i] = new Path( srcFnames[i] );
			}
		    FileInputFormat.setInputPaths(job, paths);
			
		    //set output format
		    job.setOutputFormat(oi.outputFormatClass);
		    
		    //set output path
		    MapReduceTool.deleteFileIfExistOnHDFS(fnameNew);
		    FileOutputFormat.setOutputPath(job, pathNew);
		    

			//////
			//set optimization parameters

			//set the number of mappers and reducers 
		    //job.setNumMapTasks( _numMappers ); //use default num mappers
		    long reducerGroups = _numReducers;
		    if( oi == OutputInfo.BinaryBlockOutputInfo )
		    	reducerGroups = Math.max(rlen/brlen,1) * Math.max(clen/bclen, 1); 
		    else //textcell/binarycell
		    	reducerGroups = Math.max((rlen*clen)/StagingFileUtils.CELL_BUFFER_SIZE, 1);
			job.setNumReduceTasks( (int)Math.min( _numReducers, reducerGroups) ); 	

			//use FLEX scheduler configuration properties
			if( ParForProgramBlock.USE_FLEX_SCHEDULER_CONF )
			{
				job.setInt("flex.map.min", 0);
				job.setInt("flex.map.max", _numMappers);
				job.setInt("flex.reduce.min", 0);
				job.setInt("flex.reduce.max", _numMappers);
			}
			
			//disable automatic tasks timeouts and speculative task exec
			job.setInt("mapred.task.timeout", 0);			
			job.setMapSpeculativeExecution(false);
			
			//enables the reuse of JVMs (multiple tasks per MR task)
			if( _jvmReuse )
				job.setNumTasksToExecutePerJvm(-1); //unlimited
			
			//enables compression - not conclusive for different codecs (empirically good compression ratio, but significantly slower)
			//job.set("mapred.compress.map.output", "true");
			//job.set("mapred.map.output.compression.codec", "org.apache.hadoop.io.compress.GzipCodec");
			
			//set the replication factor for the results
			job.setInt("dfs.replication", _replication);
			
			//set the max number of retries per map task
			job.setInt("mapreduce.map.maxattempts", _max_retry);
			
			//set unique working dir
			ExecMode mode = ExecMode.CLUSTER;
			MRJobConfiguration.setUniqueWorkingDir(job, mode);
			
			/////
			// execute the MR job	
			
			JobClient.runJob(job);
		
			//maintain dml script counters
			Statistics.incrementNoOfExecutedMRJobs();
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException(ex);
		}		
	}
	
}
