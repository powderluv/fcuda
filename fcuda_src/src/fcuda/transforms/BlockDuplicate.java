package fcuda.transforms;
import fcuda.utils.*;
import fcuda.common.*;

import java.util.*;

import cetus.hir.*;
//import cetus.analysis.*;
//import cetus.exec.*;
//import cetus.transforms.*;

/*
 * Pass to duplicate all variables and statements for each threadBlock
 */

public class BlockDuplicate extends KernelTransformPass
{
  private Procedure mProcedure;

  private boolean inStreamProc;

  private HashSet<Statement> mIgnoreStmts;
  private HashSet<Statement> mCoreStmts;
  private HashMap<CompoundStatement, LinkedList<Statement>> mBlockStmt2ToDuplicateStmts;
  private HashSet<IDExpression> mVarsToDuplicate;
  private HashSet<Procedure> mWrapperCreated;

  private TreeMap<String, Symbol> mId2sym;

  private Procedure kernelProc;
  private int numParallelThreadBlocks;

  public enum StatementNames
  {
    DefaultStatement,
    ExpressionStatement,
    WhileLoop,
    DeclarationStatement,
    CompoundStatement,
    AnnotationStatement,
    ForLoop,
    IfStatement
  }

  private StatementNames getStmtTypeId(Statement stmt)
  {
    if(stmt instanceof WhileLoop)
      return StatementNames.WhileLoop;
    if(stmt instanceof ExpressionStatement)
      return StatementNames.ExpressionStatement;
    if(stmt instanceof CompoundStatement)
      return StatementNames.CompoundStatement;
    if(stmt instanceof DeclarationStatement)
      return StatementNames.DeclarationStatement;
    if(stmt instanceof AnnotationStatement)
      return StatementNames.AnnotationStatement;
    if(stmt instanceof ForLoop)
      return StatementNames.ForLoop;
    if(stmt instanceof IfStatement)
      return StatementNames.IfStatement;
    return StatementNames.DefaultStatement;
  }

  public static String getPostFix(int coreId)
  {
    return "_m" + coreId;
  }

  public BlockDuplicate(Program program)
  {
    super(program);
    mIgnoreStmts = new HashSet<Statement>();
    mCoreStmts = new HashSet<Statement>();
    mBlockStmt2ToDuplicateStmts = new HashMap<CompoundStatement, LinkedList<Statement>>();
    mVarsToDuplicate = new HashSet<IDExpression>();
    mWrapperCreated = new HashSet<Procedure>();
    mId2sym = new TreeMap<String, Symbol>();
    clear();
  }

  public void clear()
  {
    mIgnoreStmts.clear();
    mCoreStmts.clear();
    mBlockStmt2ToDuplicateStmts.clear();
    mVarsToDuplicate.clear();
    mWrapperCreated.clear();
    mId2sym.clear();
    kernelProc = null;
  }

  public String getPassName()
  {
    return new String("[BlockDuplicate-FCUDA]");
  }

  private void replaceVariables(Traversable trav, Set<IDExpression> varsToReplace, String postFix)
  {
    System.out.println("mId2sym: "+mId2sym.toString());

    for(IDExpression currId : varsToReplace)
    {
      IDExpression newId = new Identifier(mId2sym.get(currId.toString()+postFix));
      IRTools.replaceAll(trav, currId, newId);
    }
  }

  private void doActualUpdates(CompoundStatement cStmt, HashMap<Statement, LinkedList<Statement>> stmt2DupList)
  {
    Set<Map.Entry<Statement, LinkedList<Statement>>> bigSet = stmt2DupList.entrySet();
    Iterator iter = bigSet.iterator();
    while(iter.hasNext())
    {
      Map.Entry<Statement, LinkedList<Statement>> t = (Map.Entry<Statement, LinkedList<Statement>>)iter.next();
      Statement origStmt = t.getKey();
      //*cetus-1.1* Statement lastStmt = origStmt;
      LinkedList<Statement> cloneStmts = t.getValue();
      for(Statement clone : cloneStmts)
      {
        if(clone instanceof DeclarationStatement) {
          assert(origStmt instanceof DeclarationStatement);
          cStmt.addDeclarationBefore(((DeclarationStatement)origStmt).getDeclaration(), 
              ((DeclarationStatement)clone).getDeclaration().clone());
        }
        else 
          cStmt.addStatementBefore(origStmt, clone);
        //*cetus-1.1* lastStmt = clone;
      }
      origStmt.detach();
      cloneStmts.clear();
    }
  }

  private boolean isKernelCall(ExpressionStatement expStmt) {
    boolean isCall = false;
    Expression expr = expStmt.getExpression();

    if(expr instanceof FunctionCall) {
      Expression callName = ((FunctionCall) expr).getName();
      //	    Expression kernName = FCUDAGlobalData.getConstMemKern(mProcedure);
      Expression kernName = FCUDAGlobalData.getConstMemKern();
      if(callName.compareTo(kernName) == 0)
        isCall = true;
    }

    return isCall;

  }

  private void handleKernelCall(ExpressionStatement expStmt) //, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList) 
  {
    Expression expr = expStmt.getExpression();
    if(!(expr instanceof FunctionCall))
      Tools.exit("ERROR: This is not a function call: "+expStmt.toString());
    FunctionCall kernCall = (FunctionCall) expr;

    // Store Kernel Procedure for later handling
    if(kernelProc == null)
      kernelProc = kernCall.getProcedure();
    else
      assert(kernCall.getName().compareTo(kernelProc.getName()) == 0);

    List<Expression> oldArgs = kernCall.getArguments();
    List<Expression> newArgs = new LinkedList<Expression>();
    newArgs.clear();

    //	LinkedList<Statement> myList = new LinkedList();
    //	myList.clear();
    //	Statement clone = (Statement)expStmt.clone();

    for(Expression argExp : oldArgs) {
      if(mVarsToDuplicate.contains(argExp)) {
        for(int i=0;i<numParallelThreadBlocks;++i)  {
          //*cetus-1.1*   newArgs.add(new Identifier(argExp.toString()+getPostFix(i)));
          assert(mId2sym.containsKey(argExp.toString()+getPostFix(i)));
          newArgs.add(new Identifier(mId2sym.get(argExp.toString()+getPostFix(i))));
        }
      } else {
        newArgs.add((Expression) argExp.clone());
      }
    }

    // Replace the arguments with the new ones
    kernCall.setArguments(newArgs);


    //	myList.add(clone);
    //	parentStmt2DupList.put(expStmt, myList);
  }


  private void handleIfStatement(IfStatement ifStmt, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList, Procedure proc)
  {
    if(FCUDAGlobalData.isPipelineCreated(ifStmt))
    {
      handleCompoundStatement((CompoundStatement)ifStmt.getThenStatement(), null, proc);
      handleCompoundStatement((CompoundStatement)ifStmt.getElseStatement(), null, proc);
    }	
    else
      handleDefaultStatement(ifStmt, parentStmt2DupList);
  }

  private void handleDeclarationStatement(DeclarationStatement stmt, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList)
  {
    if(!(stmt.getDeclaration() instanceof VariableDeclaration))
      Tools.exit("What kind of declaration is this ?"+stmt.toString());

    LinkedList<Statement> myList = new LinkedList<Statement>();
    myList.clear();

    VariableDeclaration varDecl = (VariableDeclaration)stmt.getDeclaration();
    //*cetus-1.1*  List<IDExpression> symbols = varDecl.getDeclaredSymbols();	
    List<IDExpression> symbols = varDecl.getDeclaredIDs();	

    //Iterator iter = symbols.iterator();
    for(IDExpression currId : symbols)
    {
      //IDExpression currId = (IDExpression)iter.next();
      //System.out.println("Variables "+currId.toString());
      mVarsToDuplicate.add(currId);
    }

    for(int i=0;i<numParallelThreadBlocks;++i)
    {
      VariableDeclaration newVarDecl = new VariableDeclaration(varDecl.getSpecifiers()); 
      String postFix = BlockDuplicate.getPostFix(i);
      for(int j=0;j<varDecl.getNumDeclarators();++j)
      {
        VariableDeclarator v = (VariableDeclarator)varDecl.getDeclarator(j);
        VariableDeclarator newV = new VariableDeclarator(v.getSpecifiers(), 
            //*cetus-1.1*   new Identifier(v.getSymbol().toString()+postFix),
            new NameID(v.getSymbolName()+postFix),
            v.getTrailingSpecifiers());
        newVarDecl.addDeclarator(newV);
        mId2sym.put(v.getSymbolName()+postFix, newV);
      }
      //System.out.println("Duplicating "+newVarDecl.toString());
      myList.add(new DeclarationStatement(newVarDecl));
    }
    parentStmt2DupList.put(stmt, myList);	
  }

  private void handleWhileLoop(WhileLoop whileStmt, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList, Procedure proc)
  {
    if(FCUDAGlobalData.isCreatedWhileLoop(whileStmt))
    {

      IfStatement ifStmt = FCUDAGlobalData.getGuardIf(whileStmt);
      mIgnoreStmts.add(ifStmt);

      handleCompoundStatement((CompoundStatement)whileStmt.getBody(), null, proc);

      Expression origExpr = ifStmt.getControlExpression();
      Expression condExpr = null;
      for(int i=0;i<numParallelThreadBlocks;++i)
      {
        Expression cloneExpr = (Expression)origExpr.clone();
        replaceVariables(cloneExpr, mVarsToDuplicate, BlockDuplicate.getPostFix(i));
        if(i==0)
          condExpr = cloneExpr;
        else
          condExpr = Symbolic.and(condExpr, cloneExpr);
      }
      ifStmt.setControlExpression(condExpr);
    }
    else
      handleDefaultStatement(whileStmt, parentStmt2DupList);
  }

  private void handleDefaultStatement(Statement stmt, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList)
  {
    LinkedList<Statement> myList = new LinkedList();
    myList.clear();

    for(int i=0;i<numParallelThreadBlocks;++i)
    {
      Statement clone = (Statement)stmt.clone();
      //Tools.printlnStatus("replace in stmt: "+clone.toString(), 0);
      replaceVariables(clone, mVarsToDuplicate, BlockDuplicate.getPostFix(i));
      myList.add(clone);
    }

    parentStmt2DupList.put(stmt, myList);	
  }

  private void handleFcudaCore(ExpressionStatement stmt, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList)
  {
    FunctionCall coreCall = (FunctionCall)(stmt.getExpression());

    //*debug* 
    System.out.println("   --- handleFcudaCore: "+coreCall.toString());

    if(FCUDAGlobalData.getCoreType(coreCall) == FcudaCoreData.COMPUTE_TYPE)
    {
      //*debug* 
      System.out.println("   --- of type COMPUTE: ");

      handleDefaultStatement(stmt, parentStmt2DupList);
      return;
    }

    if(FCUDAGlobalData.getCoreType(coreCall) == FcudaCoreData.TRANSFER_TYPE)
    {
      //*debug* 
      System.out.println("   --- of typee TRANSFER: ");

      HashMap<Statement, LinkedList<Statement>> tmpMap = new HashMap<Statement, LinkedList<Statement>>();
      handleDefaultStatement(stmt, tmpMap);
      LinkedList<Statement> dupStmts = tmpMap.get(stmt);

      LinkedList<Expression> newArgs = new LinkedList<Expression>();
      LinkedList<IDExpression> newArgIDs = new LinkedList<IDExpression>();
      LinkedList<Declaration> newParameters = new LinkedList<Declaration>();

      newArgs.clear();
      newParameters.clear();
      newArgIDs.clear();

      int threadBlockId = 0;
      for(Statement currStmt : dupStmts)
      {
        if(!(currStmt instanceof ExpressionStatement))
          Tools.exit("Duplicated stmt is not an expression stmt "+currStmt.toString());
        if(!(((ExpressionStatement)currStmt).getExpression() instanceof FunctionCall))
          Tools.exit("Duplicated stmt is not a function call "+currStmt.toString());

        FunctionCall currCall = (FunctionCall)((ExpressionStatement)currStmt).getExpression();

        int argCount = -1;
        for(Expression currArg : currCall.getArguments())
        {
          ++argCount;
          if(argCount < FCUDAGlobalData.getNumNonIDArguments())
          {
            newArgs.add((Expression)currArg.clone());
            newArgIDs.add((IDExpression)(FCUDAGlobalData.getNonIDArgument
                  (coreCall, threadBlockId, argCount).clone()));
            newParameters.add((Declaration)(FCUDAGlobalData.getNonIDParameter
                  (coreCall, threadBlockId, argCount).clone()));
          }
          else
          {
            if(!(currArg instanceof IDExpression))
              Tools.exit("Unknown argument type for tranfer function "+currArg.toString()+" "
                  + currCall.toString());
            IDExpression currId = (IDExpression)currArg;
            newArgs.add((IDExpression)currId.clone());
            newArgIDs.add((IDExpression)currId.clone());

            VariableDeclaration vOrig = FCUDAutils.getVariableDeclarationUnchecked(
                (IDExpression)coreCall.getArgument(argCount), 
                (SymbolTable)mProcedure.getBody()
                );
            VariableDeclaration vDecl = new VariableDeclaration(vOrig.getSpecifiers(),
                new VariableDeclarator(
                  ((VariableDeclarator)vOrig.getDeclarator(0)).getSpecifiers(),
                  (IDExpression)currId.clone(),
                  ((VariableDeclarator)vOrig.getDeclarator(0)).getTrailingSpecifiers()
                  ));
            if(vDecl == null)
              Tools.exit("Could not find declaration for "+currId.toString());
            newParameters.add((Declaration)vDecl.clone());
          }
        }
        threadBlockId++;
      }

      FunctionCall wrapper = FCUDAutils.createWrapperTransferFunction(coreCall, 
          numParallelThreadBlocks,
          mProcedure,
          newParameters, newArgs, newArgIDs,
          !mWrapperCreated.contains(coreCall.getProcedure()) );
      mWrapperCreated.add(coreCall.getProcedure());
      ExpressionStatement wrapperStmt = new ExpressionStatement(wrapper);

      LinkedList<Statement> myList = new LinkedList<Statement>();
      myList.clear();
      myList.add(wrapperStmt);

      parentStmt2DupList.put(stmt, myList);

      return;
    }
    Tools.exit("Unknown FCUDA core type : "+stmt.toString());
  } // handleFcudaCore()

  private void handleForLoop(ForLoop forLoop, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList, Procedure proc)
  {
    if(FCUDAGlobalData.getInnermostBlockIdxForLoop(proc).equals(forLoop))
    {
      ExpressionStatement assignStmt = FCUDAGlobalData.getBlockIdxForLoopAssignmentStmt(forLoop);
      mIgnoreStmts.add(assignStmt);
    }

    handleCompoundStatement((CompoundStatement)forLoop.getBody(), null, proc);

    if(FCUDAGlobalData.getInnermostBlockIdxForLoop(proc).equals(forLoop))
    {
      Expression stepExpr = forLoop.getStep();
      if(stepExpr instanceof BinaryExpression)
        ((BinaryExpression)stepExpr).setRHS(new IntegerLiteral(numParallelThreadBlocks));
      else
        if(stepExpr instanceof UnaryExpression)
        {
          BinaryExpression newStepExpr = new AssignmentExpression(
              (Expression)((UnaryExpression)stepExpr).getExpression().clone(),
              AssignmentOperator.ADD,
              new IntegerLiteral(numParallelThreadBlocks)
              );
          forLoop.setStep(newStepExpr);
        }
        else
          Tools.exit("What kind of expression is step expr "+stepExpr.toString()+" in for loop : \n"
              + forLoop.toString());

      ExpressionStatement assignStmt = FCUDAGlobalData.getBlockIdxForLoopAssignmentStmt(forLoop);
      CompoundStatement bodyStmt = (CompoundStatement)forLoop.getBody();
      for(int i=0;i<numParallelThreadBlocks;++i)
      {
        ExpressionStatement newStmt = new ExpressionStatement(new AssignmentExpression(
              (Expression)FCUDAutils.getBidxID(i, 0),
              AssignmentOperator.NORMAL,
              new BinaryExpression(
                (Expression)WrapBlockIdxLoop.getBidxCounterID(0, (SymbolTable)mProcedure.getBody()).clone(),
                BinaryOperator.ADD,
                new IntegerLiteral(i)
                )
              )
            );
        bodyStmt.addStatementBefore(assignStmt, newStmt);
      }
      assignStmt.detach();
    }
  }

  private void handleCompoundStatement(CompoundStatement cStmt, HashMap<Statement, LinkedList<Statement>> parentStmt2DupList, Procedure proc)
  {
    HashMap<Statement, LinkedList<Statement>> myStmt2DupList;
    myStmt2DupList = new HashMap<Statement, LinkedList<Statement>>();

    //Store all variables declared above this compound statement
    HashSet<IDExpression> tmpIdSet = new HashSet<IDExpression>();
    tmpIdSet.clear();
    tmpIdSet.addAll(mVarsToDuplicate);
    mVarsToDuplicate.addAll(FCUDAGlobalData.getConstMems());

    System.out.println("mVarsToDuplicate: "+ mVarsToDuplicate.toString());

    FlatIterator flatIter = new FlatIterator(cStmt);
    while(flatIter.hasNext())
    {
      Object currObj = flatIter.next();
      if(!(currObj instanceof Statement))
        Tools.exit("Child "+currObj.toString()+" of compound statement is not statement");
      Statement currStmt = (Statement)currObj;

      if(mIgnoreStmts.contains(currStmt))
        continue;

      //Comment : a visitor would have been nice :|
      switch(getStmtTypeId(currStmt))
      {
        case CompoundStatement:
          handleCompoundStatement((CompoundStatement)currStmt, null, proc);
          break;
        case ExpressionStatement:
          if(mCoreStmts.contains(currStmt))
            handleFcudaCore((ExpressionStatement)currStmt, myStmt2DupList);
          else if(inStreamProc && isKernelCall((ExpressionStatement)currStmt))
            handleKernelCall((ExpressionStatement)currStmt);
          else
            handleDefaultStatement(currStmt, myStmt2DupList);
          break;
        case DeclarationStatement:
          handleDeclarationStatement((DeclarationStatement)currStmt, myStmt2DupList);
          break;
        case WhileLoop:
          handleWhileLoop((WhileLoop)currStmt, myStmt2DupList, proc);
          break;
        case AnnotationStatement:
          break;
        case ForLoop:
          if(FCUDAGlobalData.isCreatedForLoop((ForLoop)currStmt) ||
              //			   FCUDAGlobalData.isStreamForLoop((ForLoop)currStmt, mProcedure))
            (inStreamProc && FCUDAGlobalData.isStreamForLoop((ForLoop)currStmt)))
              handleForLoop((ForLoop)currStmt, myStmt2DupList, proc);
          else
            handleDefaultStatement(currStmt, myStmt2DupList);
          break;
        case IfStatement:
          IfStatement ifStmt = (IfStatement)currStmt;
          if(FCUDAGlobalData.isPipelineCreated(ifStmt))
            handleIfStatement(ifStmt, null, proc);
          else
            handleDefaultStatement(currStmt, myStmt2DupList);
          break;
        default:
          handleDefaultStatement(currStmt, myStmt2DupList);
          break;
      }
    }

    doActualUpdates(cStmt, myStmt2DupList);
    myStmt2DupList.clear();

    //Restore variables above this compound statement
    mVarsToDuplicate.clear();
    mVarsToDuplicate.addAll(tmpIdSet);
    tmpIdSet.clear();
    myStmt2DupList.clear();
  }

  public void runPass(Procedure proc)
  {
    mProcedure = proc;

    // Handle bodies of current procedure
    List<FunctionCall> fcudaCores = FCUDAGlobalData.getFcudaCores(mProcedure);

    // *debug*
    System.out.println("fcudaCores:\n"+fcudaCores.toString());
    System.out.println("coreNames: \n"+FCUDAGlobalData.getCoreNames().toString());
    System.out.println("------------------------");

    for(FunctionCall currCore : fcudaCores)
    {
      mCoreStmts.add(FCUDAutils.getClosestParentStmt(currCore));
    }

    mIgnoreStmts.addAll(FCUDAGlobalData.getIgnoreDuringDuplicationSet());
    handleCompoundStatement(proc.getBody(), null, proc);

    // *debug*
    System.out.println("... handleCompoundStatement finished! ");

  } // runPass()

  public void transformProcedure(Procedure proc)
  {
    numParallelThreadBlocks = FCUDAGlobalData.getNumParallelThreadBlocks(proc);
    runPass(proc);
  }

}

