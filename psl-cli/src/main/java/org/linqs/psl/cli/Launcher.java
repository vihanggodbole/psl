/*
 * This file is part of the PSL software.
 * Copyright 2011-2015 University of Maryland
 * Copyright 2013-2019 The Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.linqs.psl.cli;

import org.linqs.psl.application.groundrulestore.GroundRuleStore;
import org.linqs.psl.application.inference.InferenceApplication;
import org.linqs.psl.application.inference.MPEInference;
import org.linqs.psl.application.learning.weight.WeightLearningApplication;
import org.linqs.psl.application.learning.weight.maxlikelihood.MaxLikelihoodMPE;
import org.linqs.psl.database.DataStore;
import org.linqs.psl.database.Database;
import org.linqs.psl.database.Partition;
import org.linqs.psl.database.rdbms.RDBMSDataStore;
import org.linqs.psl.database.rdbms.driver.DatabaseDriver;
import org.linqs.psl.database.rdbms.driver.H2DatabaseDriver;
import org.linqs.psl.database.rdbms.driver.H2DatabaseDriver.Type;
import org.linqs.psl.database.rdbms.driver.PostgreSQLDriver;
import org.linqs.psl.evaluation.statistics.Evaluator;
import org.linqs.psl.model.Model;
import org.linqs.psl.model.atom.GroundAtom;
import org.linqs.psl.model.predicate.StandardPredicate;
import org.linqs.psl.model.rule.GroundRule;
import org.linqs.psl.model.rule.UnweightedGroundRule;
import org.linqs.psl.model.rule.WeightedGroundRule;
import org.linqs.psl.model.term.Constant;
import org.linqs.psl.parser.ModelLoader;
import org.linqs.psl.parser.CommandLineLoader;
import org.linqs.psl.util.Reflection;
import org.linqs.psl.util.StringUtils;
import org.linqs.psl.util.Version;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Launches PSL from the command line.
 * Supports inference and supervised parameter learning.
 */
public class Launcher {
    public static final String OPERATION_INFER = "i";
    public static final String OPERATION_INFER_LONG = "infer";
    public static final String OPTION_HELP = "h";
    public static final String OPTION_HELP_LONG = "help";
    public static final String OPERATION_LEARN = "l";
    public static final String OPERATION_LEARN_LONG = "learn";
    
    public static final String OPTION_DB_H2_PATH = "h2path";
    public static final String OPTION_DB_POSTGRESQL_NAME = "postgres";
    public static final String OPTION_OUTPUT_GROUND_RULES_LONG = "groundrules";
    public static final String OPTION_DATA = "d";
    public static final String OPTION_DATA_LONG = "data";
    public static final String OPTION_EVAL = "e";
    public static final String OPTION_EVAL_LONG = "eval";
    public static final String OPTION_INT_IDS = "int";
    public static final String OPTION_MODEL = "m";
    public static final String OPTION_MODEL_LONG = "model";
    public static final String OPTION_OUTPUT_DIR = "o";
    public static final String OPTION_OUTPUT_DIR_LONG = "output";
    public static final String OPTION_OUTPUT_SATISFACTION_LONG = "satisfaction";
    public static final String OPTION_PROPERTIES = "D";
    public static final String OPTION_VERSION = "v";
    public static final String OPTION_VERSION_LONG = "version";

    public static final String MODEL_FILE_EXTENSION = ".psl";
    public static final String DEFAULT_H2_DB_PATH =
            Paths.get(System.getProperty("java.io.tmpdir"),
            "cli_" + System.getProperty("user.name") + "@" + getHostname()).toString();
    public static final String DEFAULT_POSTGRES_DB_NAME = "psl_cli";
    public static final String DEFAULT_IA = MPEInference.class.getName();
    public static final String DEFAULT_WLA = MaxLikelihoodMPE.class.getName();

    // Reserved partition names.
    public static final String PARTITION_NAME_OBSERVATIONS = "observations";
    public static final String PARTITION_NAME_TARGET = "targets";
    public static final String PARTITION_NAME_LABELS = "truth";

    private CommandLine options;
    private Logger log;

    private Launcher(CommandLineLoader commandLineLoader) {
        this.options = commandLineLoader.options;
        this.log = commandLineLoader.log;
    }

    /**
     * Set up the DataStore.
     */
    private DataStore initDataStore() {
        String dbPath = DEFAULT_H2_DB_PATH;
        boolean useH2 = true;

        if (options.hasOption(OPTION_DB_H2_PATH)) {
            dbPath = options.getOptionValue(OPTION_DB_H2_PATH);
        } else if (options.hasOption(OPTION_DB_POSTGRESQL_NAME)) {
            dbPath = options.getOptionValue(OPTION_DB_POSTGRESQL_NAME, DEFAULT_POSTGRES_DB_NAME);
            useH2 = false;
        }

        DatabaseDriver driver = null;
        if (useH2) {
            driver = new H2DatabaseDriver(Type.Disk, dbPath, true);
        } else {
            driver = new PostgreSQLDriver(dbPath, true);
        }

        return new RDBMSDataStore(driver);
    }

    private Set<StandardPredicate> loadData(DataStore dataStore) {
        log.info("Loading data");

        Set<StandardPredicate> closedPredicates;
        try {
            String path = options.getOptionValue(OPTION_DATA);
            closedPredicates = DataLoader.load(dataStore, path, options.hasOption(OPTION_INT_IDS));
        } catch (ConfigurationException | FileNotFoundException ex) {
            throw new RuntimeException("Failed to load data.", ex);
        }

        log.info("Data loading complete");

        return closedPredicates;
    }

    /**
     * Possible output the ground rules.
     * @param path where to output the ground rules. Use stdout if null.
     */
    private void outputGroundRules(GroundRuleStore groundRuleStore, String path, boolean includeSatisfaction) {
        PrintStream stream = System.out;
        boolean closeStream = false;

        if (path != null) {
            try {
                stream = new PrintStream(path);
                closeStream = true;
            } catch (IOException ex) {
                log.error(String.format("Unable to open file (%s) for ground rules, using stdout instead.", path), ex);
            }
        }

        // Write a header.
        String header = StringUtils.join("\t", "Weight", "Squared?", "Rule");
        if (includeSatisfaction) {
            header = StringUtils.join("\t", header, "Satisfaction");
        }
        stream.println(header);

        for (GroundRule groundRule : groundRuleStore.getGroundRules()) {
            String row = "";
            double satisfaction = 0.0;

            if (groundRule instanceof WeightedGroundRule) {
                WeightedGroundRule weightedGroundRule = (WeightedGroundRule)groundRule;
                row = StringUtils.join("\t",
                        "" + weightedGroundRule.getWeight(), "" + weightedGroundRule.isSquared(), groundRule.baseToString());
                satisfaction = 1.0 - weightedGroundRule.getIncompatibility();
            } else {
                UnweightedGroundRule unweightedGroundRule = (UnweightedGroundRule)groundRule;
                row = StringUtils.join("\t", ".", "" + false, groundRule.baseToString());
                satisfaction = 1.0 - unweightedGroundRule.getInfeasibility();
            }

            if (includeSatisfaction) {
                row = StringUtils.join("\t", row, "" + satisfaction);
            }

            stream.println(row);
        }

        if (closeStream) {
            stream.close();
        }
    }

    /**
     * Run inference.
     * The caller is responsible for closing the database.
     */
    private Database runInference(Model model, DataStore dataStore, Set<StandardPredicate> closedPredicates, String inferenceName) {
        log.info("Starting inference with class: {}", inferenceName);

        // Create database.
        Partition targetPartition = dataStore.getPartition(PARTITION_NAME_TARGET);
        Partition observationsPartition = dataStore.getPartition(PARTITION_NAME_OBSERVATIONS);
        Database database = dataStore.getDatabase(targetPartition, closedPredicates, observationsPartition);

        InferenceApplication inferenceApplication =
                InferenceApplication.getInferenceApplication(inferenceName, model, database);

        if (options.hasOption(OPTION_OUTPUT_GROUND_RULES_LONG)) {
            String path = options.getOptionValue(OPTION_OUTPUT_GROUND_RULES_LONG);
            outputGroundRules(inferenceApplication.getGroundRuleStore(), path, false);
        }

        inferenceApplication.inference();

        if (options.hasOption(OPTION_OUTPUT_SATISFACTION_LONG)) {
            String path = options.getOptionValue(OPTION_OUTPUT_SATISFACTION_LONG);
            outputGroundRules(inferenceApplication.getGroundRuleStore(), path, true);
        }

        log.info("Inference Complete");

        // Output the results.
        outputResults(database, dataStore, closedPredicates);

        return database;
    }

    private void outputResults(Database database, DataStore dataStore, Set<StandardPredicate> closedPredicates) {
        // Set of open predicates
        Set<StandardPredicate> openPredicates = dataStore.getRegisteredPredicates();
        openPredicates.removeAll(closedPredicates);

        // If we are just writing to the console, use a more human-readable format.
        if (!options.hasOption(OPTION_OUTPUT_DIR)) {
            for (StandardPredicate openPredicate : openPredicates) {
                for (GroundAtom atom : database.getAllGroundRandomVariableAtoms(openPredicate)) {
                    System.out.println(atom.toString() + " = " + atom.getValue());
                }
            }

            return;
        }

        // If we have an output directory, then write a different file for each predicate.
        String outputDirectoryPath = options.getOptionValue(OPTION_OUTPUT_DIR);
        File outputDirectory = new File(outputDirectoryPath);

        // mkdir -p
        outputDirectory.mkdirs();

        for (StandardPredicate openPredicate : openPredicates) {
            try {
                FileWriter predFileWriter = new FileWriter(new File(outputDirectory, openPredicate.getName() + ".txt"));

                for (GroundAtom atom : database.getAllGroundRandomVariableAtoms(openPredicate)) {
                    for (Constant term : atom.getArguments()) {
                        predFileWriter.write(term.toString() + "\t");
                    }
                    predFileWriter.write(Double.toString(atom.getValue()));
                    predFileWriter.write("\n");
                }

                predFileWriter.close();
            } catch (IOException ex) {
                log.error("Exception writing predicate {}", openPredicate);
            }
        }
    }

    private void learnWeights(Model model, DataStore dataStore, Set<StandardPredicate> closedPredicates, String wlaName) {
        log.info("Starting weight learning with learner: " + wlaName);

        Partition targetPartition = dataStore.getPartition(PARTITION_NAME_TARGET);
        Partition observationsPartition = dataStore.getPartition(PARTITION_NAME_OBSERVATIONS);
        Partition truthPartition = dataStore.getPartition(PARTITION_NAME_LABELS);

        Database randomVariableDatabase = dataStore.getDatabase(targetPartition, closedPredicates, observationsPartition);
        Database observedTruthDatabase = dataStore.getDatabase(truthPartition, dataStore.getRegisteredPredicates());

        WeightLearningApplication learner = WeightLearningApplication.getWLA(wlaName, model.getRules(),
                randomVariableDatabase, observedTruthDatabase);
        learner.learn();

        if (options.hasOption(OPTION_OUTPUT_GROUND_RULES_LONG)) {
            String path = options.getOptionValue(OPTION_OUTPUT_GROUND_RULES_LONG);
            outputGroundRules(learner.getGroundRuleStore(), path, false);
        }

        learner.close();

        if (options.hasOption(OPTION_OUTPUT_SATISFACTION_LONG)) {
            String path = options.getOptionValue(OPTION_OUTPUT_SATISFACTION_LONG);
            outputGroundRules(learner.getGroundRuleStore(), path, true);
        }

        randomVariableDatabase.close();
        observedTruthDatabase.close();

        log.info("Weight learning complete");

        String modelFilename = options.getOptionValue(OPTION_MODEL);

        String learnedFilename;
        int prefixPos = modelFilename.lastIndexOf(MODEL_FILE_EXTENSION);
        if (prefixPos == -1) {
            learnedFilename = modelFilename + MODEL_FILE_EXTENSION;
        } else {
            learnedFilename = modelFilename.substring(0, prefixPos) + "-learned" + MODEL_FILE_EXTENSION;
        }
        log.info("Writing learned model to {}", learnedFilename);

        String outModel = model.asString();

        // Remove excess parens.
        outModel = outModel.replaceAll("\\( | \\)", "");

        try (FileWriter learnedFileWriter = new FileWriter(new File(learnedFilename))) {
            learnedFileWriter.write(outModel);
        } catch (IOException ex) {
            log.error("Failed to write learned model:\n" + outModel);
            throw new RuntimeException("Failed to write learned model to: " + learnedFilename, ex);
        }
    }

    /**
     * Run eval.
     * @param predictionDatabase can be passed in to speed up evaluation. If null, one will be created and closed internally.
     */
    private void evaluation(DataStore dataStore, Database predictionDatabase, Set<StandardPredicate> closedPredicates, String evalClassName) {
        log.info("Starting evaluation with class: {}.", evalClassName);

        // Set of open predicates
        Set<StandardPredicate> openPredicates = dataStore.getRegisteredPredicates();
        openPredicates.removeAll(closedPredicates);

        // Create database.
        Partition targetPartition = dataStore.getPartition(PARTITION_NAME_TARGET);
        Partition observationsPartition = dataStore.getPartition(PARTITION_NAME_OBSERVATIONS);
        Partition truthPartition = dataStore.getPartition(PARTITION_NAME_LABELS);

        boolean closePredictionDB = false;
        if (predictionDatabase == null) {
            closePredictionDB = true;
            predictionDatabase = dataStore.getDatabase(targetPartition, closedPredicates, observationsPartition);
        }

        Database truthDatabase = dataStore.getDatabase(truthPartition, dataStore.getRegisteredPredicates());

        Evaluator evaluator = (Evaluator)Reflection.newObject(evalClassName);

        for (StandardPredicate targetPredicate : openPredicates) {
            // Before we run evaluation, ensure that the truth database actaully has instances of the target predicate.
            if (truthDatabase.countAllGroundAtoms(targetPredicate) == 0) {
                log.info("Skipping evaluation for {} since there are no ground truth atoms", targetPredicate);
                continue;
            }

            evaluator.compute(predictionDatabase, truthDatabase, targetPredicate, !closePredictionDB);
            log.info("Evaluation results for {} -- {}", targetPredicate.getName(), evaluator.getAllStats());
        }

        if (closePredictionDB) {
            predictionDatabase.close();
        }
        truthDatabase.close();
    }

    private Model loadModel(DataStore dataStore) {
        log.info("Loading model from {}", options.getOptionValue(OPTION_MODEL));

        Model model = null;

        try (FileReader reader = new FileReader(new File(options.getOptionValue(OPTION_MODEL)))) {
            model = ModelLoader.load(dataStore, reader);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load model from file: " + options.getOptionValue(OPTION_MODEL), ex);
        }

        log.debug(model.toString());
        log.info("Model loading complete");

        return model;
    }

    private void run() {
        log.info("Running PSL CLI Version {}", Version.getFull());
        DataStore dataStore = initDataStore();

        // Load data
        Set<StandardPredicate> closedPredicates = loadData(dataStore);

        // Load model
        Model model = loadModel(dataStore);

        // Inference
        Database evalDB = null;
        if (options.hasOption(OPERATION_INFER)) {
            evalDB = runInference(model, dataStore, closedPredicates, options.getOptionValue(OPERATION_INFER, DEFAULT_IA));
        } else if (options.hasOption(OPERATION_LEARN)) {
            learnWeights(model, dataStore, closedPredicates, options.getOptionValue(OPERATION_LEARN, DEFAULT_WLA));
        } else {
            throw new IllegalArgumentException("No valid operation provided.");
        }

        // Evaluation
        if (options.hasOption(OPTION_EVAL)) {
            for (String evaluator : options.getOptionValues(OPTION_EVAL)) {
                evaluation(dataStore, evalDB, closedPredicates, evaluator);
            }

            log.info("Evaluation complete.");
        }

        if (evalDB != null) {
            evalDB.close();
        }

        dataStore.close();
    }

    private static boolean isInputSanitized(CommandLineLoader commandLineLoader) {
        CommandLine options = commandLineLoader.options;
        // if only help or version was queried return
        if ((options.hasOption(CommandLineLoader.OPTION_HELP)) || 
        (options.hasOption(CommandLineLoader.OPTION_VERSION))) {
            return false;
        }

        // Data and model are required.
        // (We don't enforce them earlier so we can have successful runs with help and version.)

        if (!(options.hasOption(OPTION_DATA))) {
            System.out.println(String.format("Missing required option: --%s/-%s.", OPTION_DATA_LONG, OPTION_DATA));
            //shrbs:FIXME getHelpFormatter().printHelp("psl", options, true);
            return false;
        }
        if (!(options.hasOption(OPTION_MODEL))) {
            System.out.println(String.format("Missing required option: --%s/-%s.", OPTION_MODEL_LONG, OPTION_MODEL));
            //shrbs:FIXME getHelpFormatter().printHelp("psl", options, true);
            return false;
        }
        //FIXME: learn and infer prints
        if (!(options.hasOption(OPERATION_INFER)) && (!(options.hasOption(OPERATION_LEARN)))) {
            System.out.println(String.format("Missing required option: --%s/-%s.", OPERATION_INFER_LONG, OPERATION_INFER));
            //shrbs:FIXME getHelpFormatter().printHelp("psl", options, true);
            return false;
        }
        return true;
    }

    private static String getHostname() {
        String hostname = "unknown";

        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            // log.warn("Hostname can not be resolved, using '" + hostname + "'.");
        }

        return hostname;
    }

    public static void main(String[] args) {
        main(args, false);
    }

    public static void main(String[] args, boolean rethrow) {
        try {
            CommandLineLoader commandLineLoader = new CommandLineLoader(args);
            if (!(isInputSanitized(commandLineLoader))) {
                return;
            }
            Launcher pslLauncher = new Launcher(commandLineLoader);
            pslLauncher.run();
        } catch (Exception ex) {
            if (rethrow) {
                throw new RuntimeException("Failed to run CLI: " + ex.getMessage(), ex);
            } else {
                System.err.println("Unexpected exception!");
                ex.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }
}
