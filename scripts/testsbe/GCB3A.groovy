@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*
import com.ibm.jzos.ZFile
import com.ibm.dbb.build.report.*
import com.ibm.dbb.build.report.records.*


// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils= loadScript(new File("${props.DBBBuildDir}/utilities/BuildUtilities.groovy"))
@Field def impactUtils= loadScript(new File("${props.DBBBuildDir}/utilities/ImpactUtilities.groovy"))
@Field def bindUtils= loadScript(new File("${props.DBBBuildDir}/utilities/BindUtilities.groovy"))
@Field RepositoryClient repositoryClient

// verify required build properties
buildUtils.assertBuildProperties(props.cobol_requiredBuildProperties)

println("** Building files mapped to ${this.class.getName()}.groovy script")

// create language datasets
def langQualifier = "cobol"
buildUtils.createLanguageDatasets(langQualifier)

// save buildFiles from script arguments
List<String> buildFiles = argMap.buildList

// iterate through build list
buildFiles.each { buildFile ->
	println "*** Building file $buildFile"

	// load the element properties, processor default and overriding properties
	populateBuildProperties(buildFile)

	// copy source file and dependencies
	DependencyResolver dependencyResolver = buildUtils.createDependencyResolver(buildFile, "${props.dependency_resolutionRules}")
	buildUtils.copySourceFiles(buildFile, props.cobol_srcPDS, 'cobol_dependenciesDatasetMapping', props.cobol_dependenciesAlternativeLibraryNameMapping, dependencyResolver)

	//create logical file and log file
	LogicalFile logicalFile = dependencyResolver.getLogicalFile()
	String C1ELEMENT = CopyToPDS.createMemberName(buildFile)
	String logName = "${props.ENV}.${props.STG}.${props.SYS}.${props.SUB}.${C1ELEMENT}.${props.TYP}"
	File logFile = new File( props.userBuild ? "${props.buildOutDir}/${C1ELEMENT}.log" : "${props.buildOutDir}/${logName}.log")
	if (logFile.exists())
		logFile.delete()

	// create mvs commands
	MVSExec sql = createSqlCommand(buildFile, logicalFile, C1ELEMENT, logFile)
	MVSExec syscincopy = createSyscinCopyCommand(buildFile, logicalFile, C1ELEMENT, logFile)
	MVSExec trn = createTrnCommand(buildFile, logicalFile, C1ELEMENT, logFile)
	MVSExec syspunchcopy = createSyspunchCopyCommand(buildFile, logicalFile, C1ELEMENT, logFile)
	//	MVSExec compile = createCompileCommand(buildFile, logicalFile, C1ELEMENT, logFile)
	//	MVSExec lked1 = createLked1Command(buildFile, logicalFile, member, logFile)
	//	MVSExec lked2 = createLked2Command(buildFile, logicalFile, member, logFile)
	//	MVSExec dbrmcopy = createDbrmcopyCommand(buildFile, logicalFile, member, logFile)

	// execute mvs commands in a mvs job
	MVSJob job = new MVSJob()
	job.start()

	//sql step
	props.COB3DYN = "N"
	props."@DB2" = "Y"
	if("${props.COB3DYN}".toString().equals("N") &&
	"${props."@DB2"}".toString().equals("Y"))
	{
		def sqlrc = sql.execute()
		if (sqlrc > 4)
		{
			println("db2 Pre Compile failed!  RC=$sqlrc")
			String errorMsg = "*The db2 Pre compile return code ($sqlrc) for $buildFile"
			buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${logName}.log":logFile],client:getRepositoryClient())
		}
		else
		{
			println("db2 Pre Compile successful!  RC=$sqlrc")
			String successMsg = "*The db2 Pre compile return code ($sqlrc) for $buildFile"
			buildUtils.updateBuildResult(successMsg:successMsg,logs:["${logName}.log":logFile],client:getRepositoryClient())
			def copyrc= syscincopy.execute()
			if (copyrc > 4)
				println("copy failed!  RC=$copyrc")
			else
				println("copy successful!  RC=$copyrc")
		}
	}
	//	trn step
	props.COB3DYN = "N"
	props."@CIC" = "Y"
	props."@XDL" = "Y"
	if("${props.COB3DYN}".toString().equals("N") &&
	("${props."@CIC"}".toString().equals("Y") || "${props."@XDL"}".toString().equals("Y")))
	{
		def trnrc = trn.execute()
		if (trnrc > 4)
		{
			println("trn failed!  RC=$trnrc")
			String errorMsg = "trn return code ($trnrc) for $buildFile"
			buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${logName}.log":logFile],client:getRepositoryClient())
		}
		else
		{
			println("trn successful!  RC=$trnrc")
			String successMsg = "trn return code ($trnrc) for $buildFile"
			buildUtils.updateBuildResult(successMsg:successMsg,logs:["${logName}.log":logFile],client:getRepositoryClient())
			def trncopyrc = syspunchcopy.execute()
			if (trncopyrc > 4)
				println("copy failed!  RC=$trncopyrc")
			else
				println("copy successful!  RC=$trncopyrc")
		}
	}

	job.stop()

}

// create method definitions

def createSqlCommand(String buildFile, LogicalFile logicalFile, String C1ELEMENT, File logFile) {
	def sql = new MVSExec().pgm("DSNHPC").parm("${props.DB2OPT}")
	sql.dd(new DDStatement().name("TASKLIB").dsn("${props.DB2EXIT}").options("shr"))
	sql.dd(new DDStatement().dsn("${props.DB2LOAD}").options("shr"))
	sql.dd(new DDStatement().name("SYSPRINT").options("cyl space(1,2) unit(vio) new"))
	sql.dd(new DDStatement().name("SYSTERM").options("DUMMY"))
	sql.dd(new DDStatement().name("SYSUT1").options("tracks space(15,15) unit(vio) new"))
	sql.dd(new DDStatement().name("SYSUT2").options("tracks space(5,5) unit(vio) new"))
	sql.dd(new DDStatement().name("SYSLIB").dsn("${props.DB2DCLG}").options("shr"))
	sql.dd(new DDStatement().name("DBRMLIB").dsn("${props.cobol_dbrmPDS}(${C1ELEMENT})").options("shr"))
	sql.dd(new DDStatement().name("SYSIN").dsn("${props.cobol_srcPDS}(${C1ELEMENT})").options("shr"))
	sql.dd(new DDStatement().name("SYSCIN").dsn("&&SYSCIN").options("tracks space(15,15) unit(vio) new").pass(true))
	sql.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))
	return sql
}

def createSyscinCopyCommand(String buildFile, LogicalFile logicalFile, String C1ELEMENT, File logFile) {
	def syscincopy = new MVSExec().pgm("IEBGENER")
	syscincopy.dd(new DDStatement().name("SYSIN").options("DUMMY"))
	syscincopy.dd(new DDStatement().name("SYSUT1").dsn("&&SYSCIN").options("shr"))
	syscincopy.dd(new DDStatement().name("SYSUT2").dsn("${props.cobol_srcPDS}(${C1ELEMENT}").options("shr"))
	syscincopy.dd(new DDStatement().name("SYSPRINT").options("cyl space(1,2) unit(vio) new"))
	return syscincopy
}

def createTrnCommand(String buildFile, LogicalFile logicalFile, String C1ELEMENT, File logFile) {
	def trn = new MVSExec().pgm("DFHECP1\$").parm("${props.CITRNOPT}")
	trn.dd(new DDStatement().name("TASKLIB").dsn("${props.CICSLOAD}").options("shr"))
	trn.dd(new DDStatement().name("SYSPRINT").options("cyl space(1,2) unit(vio) new"))
	trn.dd(new DDStatement().name("SYSIN").dsn("${props.cobol_srcPDS}(${C1ELEMENT})").options("shr"))
	trn.dd(new DDStatement().name("SYSPUNCH").dsn("&&SYSPUNCH").options("tracks space(15,5) unit(vio) new").pass(true))
	trn.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))
	return trn
}

def createSyspunchCopyCommand(String buildFile, LogicalFile logicalFile, String C1ELEMENT, File logFile) {
	def syspunchcopy = new MVSExec().pgm("IEBGENER")
	syspunchcopy.dd(new DDStatement().name("SYSIN").options("DUMMY"))
	syspunchcopy.dd(new DDStatement().name("SYSUT1").dsn("&&SYSPUNCH").options("shr"))
	syspunchcopy.dd(new DDStatement().name("SYSUT2").dsn("${props.cobol_srcPDS}(${C1ELEMENT}").options("shr"))
	syspunchcopy.dd(new DDStatement().name("SYSPRINT").options("cyl space(1,2) unit(vio) new"))
	return syspunchcopy
}


//def createCompileCommand(String buildFile, LogicalFile logicalFile, String C1ELEMENT, File logFile) {
//	def compil = new MVSExec().pgm("${props.COMPILER}").parm("${props.COBOPT1},${props.COBOPT2},${props.COBODEV}")
//
//	compil.dd(new DDStatement().name("SYSIN").instreamData("${props.elemnewOpts})
//	compil.dd(new DDStatement().dsn("${props.cobol_srcPDS}(${C1ELEMENT})").options("shr"))
//
//	if("${props.COB3DYN}".toString().equals("Y") &&
//	"${props."@DB2"}".toString().equals("Y"))
//	{
//	compil.dd(new DDStatement().name("DBRMLIB").dsn("${props.cobol_dbrmPDS}(${C1ELEMENT})").options("shr"))
//	}
//
//	compil.dd(new DDStatement().name("SYSLIB").dsn("SYS1.VIDE.BSCOS39S").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COCPUSR1}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COCPUSR2}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COCPUSR3}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COBSHDM}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COBODMM}").options("shr"))
//	if("${props."@CIC"}".toString().equals("Y"))
//	{
//		compil.dd(new DDStatement().dsn("${props.COBCICM}").options("shr"))
//		compil.dd(new DDStatement().dsn("${props.COBASFM}").options("shr"))
//	}
//	if("${props."@BTC"}".toString().equals("Y"))
//	{
//		compil.dd(new DDStatement().dsn("${props.COBPRNTM}").options("shr"))
//	}
//	if("${props."@DB2"}".toString().equals("Y"))
//	{
//		compil.dd(new DDStatement().dsn("${props.DB2DCLG}").options("shr"))
//	}
//	compil.dd(new DDStatement().dsn("${props.COCPSTG1}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COCPSTG2}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COSTGRCP}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.COSTGPCP}").options("shr"))
//
//	compil.dd(new DDStatement().name("SYSLIN").dsn("&&SYSLIN").options("cyl space(1,1) unit(vio) blksize(3200) new").pass(true))
//	compil.dd(new DDStatement().name("SYSPRINT").options("cyl space(3,5) unit(vio) new"))
//	compil.dd(new DDStatement().name("TASKLIB").dsn("${props.COBLIB}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.ABNLIB}").options("shr"))
//	compil.dd(new DDStatement().dsn("${props.CICSLOAD}").options("shr"))
//	if("${props."@DB2"}".toString().equals("Y"))
//	{
//		compil.dd(new DDStatement().dsn("${props.DB2EXIT}").options("shr"))
//		compil.dd(new DDStatement().dsn("${props.DB2LOAD}").options("shr"))
//	}
//	compil.dd(new DDStatement().name("SYSUT1").options("cyl space(1,1) unit(vio) new"))
//	compil.dd(new DDStatement().name("SYSUT2").options("cyl space(1,1) unit(vio) new"))
//	compil.dd(new DDStatement().name("SYSUT3").options("cyl space(1,1) unit(vio) new"))
//	compil.dd(new DDStatement().name("SYSUT4").options("cyl space(1,1) unit(vio) new"))
//	compil.dd(new DDStatement().name("SYSUT5").options("cyl space(1,1) unit(vio) new"))
//	compil.dd(new DDStatement().name("SYSUT6").options("cyl space(1,1) unit(vio) new"))
//	compil.dd(new DDStatement().name("SYSUT7").options("cyl space(1,1) unit(vio) new"))
//
//	if("${props.COMPILER}".toString().equals("CWPCMAIN"))
//	{
//		compil.dd(new DDStatement().name("CWPDDIO").dsn("${props.ABNDDIOF}").options("shr"))
//		compil.dd(new DDStatement().name("CWPERRM").options("cyl space(1,2) unit(work) new"))
//		compil.dd(new DDStatement().name("CWPWBNV").options("SYSOUT(Z)"))
//		compil.dd(new DDStatement().name("SYSOUT").options("SYSOUT(Z)"))
//	}
//	if("${props."@CIC"}".toString().equals("Y"))
//	{
//		compil.dd(new DDStatement().name("CWPPRMO").instreamData('''
//LANGUAGE(COBOLZ/OS)
//COBOL(OUTPUT(NOPRINT,NODDIO))
//PROCESSOR(OUTPUT(PRINT,DDIO))
//PROCESSOR(TEXT(NONE))
//PROCESSOR(NOBYPASS)
//PROCESSOR(ERRORS(MIXED-CASE))
//DDIO(OUTPUT(FIND,COMPRESS,NOLIST))
//PRINT(OUTPUT(SOURCE,NOLIST))
//CICSTEST(OPTIONS(WARNING))''')
//	}
//
//	if("${props."@BTC"}".toString().equals("Y"))
//	{
//		compil.dd(new DDStatement().name("CWPPRMO").instreamData('''
//LANGUAGE(COBOLZ/OS)
//COBOL(OUTPUT(NOPRINT,NODDIO))
//PROCESSOR(OUTPUT(PRINT,DDIO))
//PROCESSOR(TEXT(NONE))
//PROCESSOR(NOBYPASS)
//PROCESSOR(ERRORS(MIXED-CASE))
//DDIO(OUTPUT(FIND,COMPRESS,NOLIST))
//PRINT(OUTPUT(SOURCE,NOLIST))''')
//	}
//
//	return compil
//}

def getRepositoryClient() {
	if (!repositoryClient && props."dbb.RepositoryClient.url")
		repositoryClient = new RepositoryClient().forceSSLTrusted(true)

	return repositoryClient
}

def populateBuildProperties(String buildFile) {
	def buildFileName = buildFile.toString()
	def String[] entries = buildFileName.split("/")
	def String[] elementandtype = entries[3].split("[.]")
	def String[] envandstage ="${props.env}".split("-")
	props.ENV = envandstage[0]
	props.STG = envandstage[1]
	props.SYS = entries[1]
	props.SUB = entries[2]
	props.TYP = elementandtype[1]
	props."@@CLI"="${props.ENV}".toString().substring(0,1)
	props."@@TYP"="${props.TYP}".toString().substring(3,7)
	def elementProps = "${props.workspace}/${buildFileName}.properties"
	props.load(new File(elementProps))
	def defaultProps = "${props.DBBBuildDir}/processors/${props.processor}.defaults.properties"
	def overridingProps = "${props.workspace}/configuration/${props.ENV}-${props.STG}/${props.SYS}/${props.SUB}/${props.processor}/${props."processor-group"}/${props.TYP}.properties"
	props.load(new File(overridingProps))
	props.load(new File(defaultProps))
	props.load(new File(overridingProps))
	if (props.containsKey("@@TABDB2")) {
		boolean success = setPropsFromTabs()
		// do something if not success ?
	}

	def elmnewOpts

	elmnewOpts = ''
	if("${props."@DB2"}".toString().equals("Y") &&
	("${props."@CIC"}".toString().equals("Y") || "${props."@XDL"}".toString().equals("Y"))) {
		elmnewOpts = '''CBL SQL(&@ZQ.&DB2OPT.&@ZQ
                        CBL CICS(&@ZQ.&CITRNOPT.&@ZQ)'''
	}
	if("${props."@DB2"}".toString().equals("Y") &&
	("${props."@CIC"}".toString().equals("N") || "${props."@XDL"}".toString().equals("N"))) {
		elmnewOpts = '''CBL SQL(&@ZQ.&DB2OPT.&@ZQ)'''
	}
	if("${props."@DB2"}".toString().equals("N") &&
	("${props."@CIC"}".toString().equals("Y") || "${props."@XDL"}".toString().equals("Y"))) {
		elmnewOpts = '''CBL CICS(&@ZQ.&CITRNOPT.&@ZQ)'''
	}



}
boolean setPropsFromTabs() {
	try {
		int tabIndex =Integer.parseInt("${props."processor-group"}".substring(2,4)) - 1
		props."@BTC" = props."@@TABBTC".charAt(tabIndex).toString()
		props."@DB2" = props."@@TABDB2".charAt(tabIndex).toString()
		props."@XDL" = props."@@TABXDL".charAt(tabIndex).toString()
		props."@CIC" = props."@@TABCIC".charAt(tabIndex).toString()
		props."@LK2" = props."@@TABLK2".charAt(tabIndex).toString()
	} catch (StringIndexOutOfBoundsException | NumberFormatException ignored) {
		println("processorGroup: No valid number found at the required position")
		return false
	}
	return true
}



