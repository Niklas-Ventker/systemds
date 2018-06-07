/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.test.integration.functions.nary;

import java.util.HashMap;

import org.junit.Test;

import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.DMLScript.RUNTIME_PLATFORM;
import org.apache.sysml.lops.LopProperties.ExecType;
import org.apache.sysml.runtime.matrix.data.MatrixValue.CellIndex;
import org.apache.sysml.test.integration.AutomatedTestBase;
import org.apache.sysml.test.integration.TestConfiguration;
import org.apache.sysml.test.utils.TestUtils;

public class NaryMinMaxTest extends AutomatedTestBase
{
	private final static String TEST_NAME = "NaryMinMax";
	private final static String TEST_DIR = "functions/nary/";
	private final static String TEST_CLASS_DIR = TEST_DIR + NaryMinMaxTest.class.getSimpleName() + "/";

	private final static double epsilon=0.0000000001;
	
	private final static int rows = 2101;
	private final static int cols = 110;
	
	private final static double sparsity1 = 0.7;
	private final static double sparsity2 = 0.07;
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"R"}));
	}

	@Test
	public void testNaryMinDenseCP() {
		runMinMaxTest(true, false, ExecType.CP);
	}
	
	@Test
	public void testNaryMinSparseCP() {
		runMinMaxTest(true, true, ExecType.CP);
	}
	
	@Test
	public void testNaryMaxDenseCP() {
		runMinMaxTest(false, false, ExecType.CP);
	}
	
	@Test
	public void testNaryMaxSparseCP() {
		runMinMaxTest(false, true, ExecType.CP);
	}
	
	public void runMinMaxTest(boolean min, boolean sparse, ExecType et)
	{
		RUNTIME_PLATFORM platformOld = rtplatform;
		switch( et ) {
			case MR: rtplatform = RUNTIME_PLATFORM.HADOOP; break;
			case SPARK: rtplatform = RUNTIME_PLATFORM.SPARK; break;
			default: rtplatform = RUNTIME_PLATFORM.HYBRID_SPARK; break;
		}
		
		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		if( rtplatform == RUNTIME_PLATFORM.SPARK || rtplatform == RUNTIME_PLATFORM.HYBRID_SPARK )
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;
		
		try
		{
			TestConfiguration config = getAndLoadTestConfiguration(TEST_NAME);
			loadTestConfiguration(config);
			
			String RI_HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = RI_HOME + TEST_NAME + ".dml";
			programArgs = new String[]{"-stats", "-args", input("A"),
				input("B"), input("C"), String.valueOf(min).toUpperCase(), output("R") };
			fullRScriptName = RI_HOME + TEST_NAME + ".R";
			rCmd = "Rscript" + " " + fullRScriptName + " " + inputDir() + " " 
				+ String.valueOf(min).toUpperCase() + " "+ expectedDir();
			
			//generate input data
			double sp = sparse ? sparsity2 : sparsity1;
			double[][] A = getRandomMatrix(rows, cols, -1, 1, sp, 3);
			double[][] B = getRandomMatrix(rows, cols, -1, 1, sp, 7);
			double[][] C = getRandomMatrix(rows, cols, -1, 1, sp, 10);
			writeInputMatrixWithMTD("A", A, true);
			writeInputMatrixWithMTD("B", B, true);
			writeInputMatrixWithMTD("C", C, true);
			
			//run tests
			runTest(true, false, null, -1);
			runRScript(true);
			
			//compare result data
			HashMap<CellIndex, Double> dmlfile = readDMLMatrixFromHDFS("R");
			HashMap<CellIndex, Double> rfile = readRMatrixFromFS("R");
			TestUtils.compareMatrices(dmlfile, rfile, epsilon, "DML", "R");
		}
		finally {
			rtplatform = platformOld;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
		}
	}
}