import java.util.concurrent.RecursiveTask;

public class FireTask extends RecursiveTask<FireMapParallel.StepResult> {
    //placeholder class for automarker
    private static int minimumParallelArea = 10000;

    private final FireMapParallel map;
    private final FireMapParallel.Mode mode;
    private final int rowStart;
    private final int rowEnd;
    private final int columnStart;
    private final int columnEnd;

    FireTask(FireMapParallel map, FireMapParallel.Mode mode, int rowStart, int rowEnd, int columnStart, int columnEnd) {
        this.map = map;
        this.mode = mode;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
        this.columnStart = columnStart;
        this.columnEnd = columnEnd;
    }

    @Override
    protected FireMapParallel.StepResult compute() {
        //base condition certain size area
        //-> return updateRegion of that region
        if((rowEnd-rowStart) * (columnEnd - columnStart) <= minimumParallelArea) {
            FireMapParallel.StepResult result = map.updateRegion(mode, rowStart, rowEnd, columnStart, columnEnd);
            return result;
        }
        else {
            //if wider map, split along a column
            FireTask left;
            FireTask right;
            if((columnEnd - columnStart) > (rowEnd-rowStart)) {
                int columMidpoint = (columnEnd + columnStart)/2;
                left = new FireTask(map, mode, rowStart, rowEnd, columnStart, columMidpoint);
                right = new FireTask(map, mode, rowStart, rowEnd, columMidpoint, columnEnd);

            }
            else{
                int rowMidpoint = (rowEnd + rowStart)/2;
                left = new FireTask(map, mode, rowStart, rowMidpoint, columnStart, columnEnd);
                right = new FireTask(map, mode, rowMidpoint, rowEnd, columnStart, columnEnd);
            }
            
            left.fork();
            FireMapParallel.StepResult rightResult = right.compute();
            FireMapParallel.StepResult leftResult = left.join();
            return FireMapParallel.StepResult.combine(leftResult,rightResult);
        }

    }
}

