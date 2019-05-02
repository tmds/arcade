import hudson.tasks.*;
import org.jenkinsci.plugins.ghprb.GhprbTrigger

def folder = Jenkins.instance.getItemByFullName("/dotnet_cli/release_2.1.7xx/")

def newAzdoDef = processFolder(folder, out);
println("=====================================")
println("\n\n\n")
newAzdoDef.emit(out)

def processFolder(Item folder, def out) {
	out.println("Processsing ${folder.getFullName()}")
	
	// Find generator and look up the basic parameters
	def generatorJob = Jenkins.instance.getItemByFullName("${folder.getFullName()}/generator")

	if (generatorJob == null) {
		out.println("Could not find generator job in folder ${folder.getFullName()}")
		assert false
	}

	def params = getParameters(generatorJob, out)
	def branchName = params["BranchName"]
	def repoName = params["QualifiedRepoName"]
	assert branchName != "" && branchName != null
	assert repoName != "" && repoName != null

	def newDef = new azdoDefinition(repoName, branchName);
	newDef.globalVars.put("Build.Repository.Clean", "true")

	def childJobs = folder.getAllJobs();
	childJobs.each { childJob ->
		if (shouldProcess(childJob)) {
			def newJob = processJob(childJob, out)
			newDef.jobs.add(newJob)
		}
	}

	return newDef;
}

def translateLabel(AbstractProject item, def out) {
	def label = item.getAssignedLabelString();
	switch(label) {
		case "rhel72-20171003":
		case "deb82-20160323":
		case "ubuntu1604-20170925-1":
		case "ubuntu1604-20180314":
		case "ubuntu1404-20180321":
		case "centos71-20171005":
			return "vmImage: ubuntu-16.04"
		case "osx-10.12 || OSX.1012.Amd64.Open":
			return "vmImage: macOS-10.13"
		case "windows.10.amd64.clientrs4.devex.15.8.open":
			return "vmImage: vs2017-win2016";
		default:
			out.println("Unknown label ${label}")
			assert false;
			break;
	}
}

def getParameters(FreeStyleProject item, def out) {
	def values = [:]
	ParametersDefinitionProperty pdp = item.getProperty(ParametersDefinitionProperty.class);
	if (pdp != null) {
		for (ParameterDefinition pd : pdp.getParameterDefinitions()) {
          	if (pd instanceof StringParameterDefinition) {
            	values.put(pd.getName(), pd.getDefaultParameterValue().getValue().toString())
          	} else if (pd instanceof BooleanParameterDefinition) {
            	values.put(pd.getName(), pd.getDefaultParameterValue().getValue().toString())
          	} else {
            	out.println("Unknown parameter definition type ${pd.getClass()}")
		    	assert false
          	}
		}
	}
	return values;
}

def shouldProcess(Item item) 
{
	def name = item.getName();
	if (name == "GenPRTest" || name == "generator_prtest") {
		return false;
	}

	if (name.indexOf("_prtest") == -1) {
		return false;
	}

	// if it's not a FreeStyleProject, skip
	if (!(item instanceof FreeStyleProject)) {
		return false
	}

	return true;
}

def santizeName(def name) {
	return name.replace(".","")
	           .replace("-", "_")
}

def processJob(Item item, def out) {
	// Find the job name.
	// Preference the string used in the ghprb trigger,
	// then the job name itself

	def jobName = item.getName().replace("_prtest", "");
	def ghprbTrigger = item.getTrigger(GhprbTrigger.class);
	if (ghprbTrigger != null) {

	}

	jobName = santizeName(jobName)

	def newJob = new azdoJob(jobName)

	newJob.pool = translateLabel(item, out)

	def params = getParameters(item, out)
	params.each { k,v -> 
		if (!ignoreParam(k)) {
			switch (k){
				default:
					out.println("Unexpected parameter name ${k}")
					assert false
			}
		}
	}

	def buildSteps = item.getBuilders()
	
	buildSteps.each { step ->
		def stepText = processBuildStep(item, step, out)
		if (stepText != null) {
			newJob.steps.add(stepText)
		}
	}

	def publishSteps = item.getPublishers()
	
	publishSteps.each { step ->
		def stepText = processPublishStep(item, step.getValue(), out)
		if (stepText != null) {
			newJob.steps.add(stepText)
		}
	}

	return newJob
}

def ignoreParam(def name) {
	return name == "GitHubProject" ||
		   name == "GitBranchOrCommit" ||
		   name == "GitRepoUrl" ||
		   name == "GitRefSpec" ||
		   name == "DOTNET_CLI_TELEMETRY_PROFILE" ||
		   name == "GithubProjectName" ||
		   name == "GithubOrgName" ||
		   name == "AutoSaveReproEnv" ||
		   name == "QualifiedRepoName" ||
		   name == "BranchName"
}

def processScript(def script) {
	if (script.indexOf("\n") == -1) {
		return script
	}
	else {
		def elements = script.split("\n"); 
		def finalString = "|"
		for (def element : elements) {
			finalString += "\n      ${element}"
		}
		return finalString
	}
}

def processPublishStep(FreeStyleProject proj, def step, def out) {
	if (step instanceof hudson.plugins.ws_cleanup.WsCleanup) {
		return null
	} else if (step instanceof hudson.plugins.repro_tool.ReproToolPublisher) {
		return null
	} else if (step instanceof org.jenkinsci.plugins.xunit.XUnitPublisher) {
		def testDataPublishers = step.getTypes()
		assert testDataPublishers.length == 1
		def testDataPub = testDataPublishers[0]
		def glob = ""
		def format = ""
		if (testDataPub instanceof org.jenkinsci.plugins.xunit.types.MSTestJunitHudsonTestType) {
			glob = testDataPub.getPattern()
			format = "VSTest"
		} else {
			out.println("Unknown test data type ${testDataPub.getClass()}")
		}
		 { tdp ->
			out.println(tdp)
		}
		return """- task: PublishTestResults@2
  inputs:
    testResultsFormat: ${format}
	testResultsFiles: ${glob}"""
	} else if (step org.jenkins__ci.plugins.flexible__publish.ConditionalPublisher) {
		def worstResult = step.getWorstResult()
		def bestResult = step.getBestResult()
		def publisherList = step.getPublisherList()
		assert publisherList.size() == 1
		def publisher = publisherList[0]
		if (publisher instanceof hudson.tasks.ArtifactArchiver) {
			// Preserve the conditional nature of the archiving, though
			// a lot of times this was done to save disk space
			return """- task: PublishBuildArtifacts@1
  inputs:
    pathtoPublish: '$(Build.ArtifactStagingDirectory)'
    artifactName: drop"""
		}
	} else if (step instanceof com.chikli.hudson.plugin.naginator.NaginatorPublisher) {
		return null
	} else if (step instanceof org.jenkins_ci.plugins.flexible_publish.FlexiblePublisher) {	
		def flexiblePublishers = step.getPublishers()
		assert flexiblePublishers.size() == 1
		def publisher = flexiblePublishers[0]
		return processBuildStep(proj, publisher, out)
	} else {
		out.println("Unknown step type ${step.getClass()}")
		assert false
	}
}

def processBuildStep(FreeStyleProject, def step, def out) {
	if (step instanceof BatchFile) {
		return "- script: ${processScript(step.getCommand().trim())}"
	} else if (step instanceof Shell) {
		return "- script: ${processScript(step.getCommand().trim())}"
	} else {
		out.println("Unknown step type ${step.getClass()}")
		assert false
	}
}

class azdoDefinition {
	def repo
	def branch
	def jobs = []
	def globalVars = [:]

	def azdoDefinition(def repo, def branch) {
		this.repo = repo
		this.branch = branch
	}

	def emit(def out) {
		emitVariables(out);
		out.println();
		emitTriggers(out);
		out.println();
		emitJobs(out)
	}

	def emitVariables(def out) {
		out.println("variables:")
		globalVars.each { k,v ->
			out.println("- name:  ${k}")
			out.println("  value: ${v}")
		}
	}

	def emitTriggers(def out) {
		out.println("trigger:")
		out.println("- ${branch}")
		out.println();
		out.println("pr:")
		out.println("- ${branch}")
	}

	def emitJobs(def out) {
		out.println("jobs:")
		jobs.each { job ->
			job.emit(out)
			out.println()
		}
	}
}

class azdoJob {
	def name
	def displayName
	def pool
	def steps = []

	def azdoJob(def name) {
		this.name = name
	}

	def emit (def out) {
		out.println("- job: ${this.name}")
		emitPool(out);
		emitSteps(out);
	}

	def emitPool(def out) {
		out.println("  pool:")
		out.println("    ${this.pool}")
	}

	def emitSteps(def out) {
		out.println("  steps:")
		steps.each { step ->
			out.println("  ${step}")
		}
	}
}