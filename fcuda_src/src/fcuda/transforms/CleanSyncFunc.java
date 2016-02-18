package fcuda.transforms;

import java.util.*;

import fcuda.utils.*;

import cetus.hir.*;
import cetus.exec.*;

public class CleanSyncFunc extends KernelTransformPass
{
  protected Expression swapwith = null;

  public String getPassName()
  {
    return new String("[CleanSyncFunc-MCUDA]");
  }

  public CleanSyncFunc(Program program)
  {
    super(program);
  }

  public void transformProcedure(Procedure proc)
  {
    Expression old_sync = MCUDAUtils.getSync();
    if (swapwith != null)
      Tools.replaceAll(proc, old_sync, swapwith);
    else {
      DepthFirstIterator iter = new DepthFirstIterator(proc);
      iter.pruneOn(Expression.class);
      while (iter.hasNext()) {
        ExpressionStatement s;
        try {
          s = (ExpressionStatement)iter.next(ExpressionStatement.class);
        } catch (NoSuchElementException e) {
          break;
        }
        if (s.getExpression().compareTo(old_sync) == 0)
          s.detach();
      }

      if (Driver.getOptionValue("Fcuda") != null) {

        List<Procedure> tskLst = FCUDAutils.getTaskMapping(proc.getSymbolName()); 

        if (tskLst != null) {
          for (Procedure task : tskLst ) {

            iter = new DepthFirstIterator(task);
            iter.pruneOn(Expression.class);
            while (iter.hasNext()) {
              ExpressionStatement s;
              try {
                s = (ExpressionStatement)iter.next(ExpressionStatement.class);
              } catch (NoSuchElementException e) {
                break;
              }
              if (s.getExpression().compareTo(old_sync) == 0)
                s.detach();
            }
          }
        }
      }

    }
  }
}
