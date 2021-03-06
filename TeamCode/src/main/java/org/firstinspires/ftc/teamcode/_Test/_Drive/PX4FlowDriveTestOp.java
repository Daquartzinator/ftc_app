package org.firstinspires.ftc.teamcode._Test._Drive;

import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.DcMotor;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.teamcode._Libs.AutoLib;
import org.firstinspires.ftc.teamcode._Libs.BNO055IMUHeadingSensor;
import org.firstinspires.ftc.teamcode._Libs.HeadingSensor;
import org.firstinspires.ftc.teamcode._Libs.PX4Flow;
import org.firstinspires.ftc.teamcode._Libs.SensorLib;

import java.util.ArrayList;


/**
 * simple example of using a Step that uses encoder and gyro input to drive to given field positions.
 * Created by phanau on 12/15/18
 */

// simple example sequence that tests PX4Flow+IMU position integration to drive along a given path
@Autonomous(name="Test: PX4Flow Drive Test", group ="Test")
//@Disabled
public class PX4FlowDriveTestOp extends OpMode {

    // use PX4Flow incremental motion and gyro to track absolute field position
    class PX4FlowGyroPosInt extends SensorLib.PositionIntegrator {

        OpMode mOpMode;
        HeadingSensor mGyro;
        PX4Flow mFlow;

        public PX4FlowGyroPosInt(OpMode opmode, PX4Flow flow, HeadingSensor gyro, Position initialPosn)
        {
            super(initialPosn);
            mOpMode = opmode;
            mGyro = gyro;
            mFlow = flow;
        }

        public boolean loop() {

            // read current integrated data from sensor
            mFlow.readIntegral();
            int dx = mFlow.pixel_flow_x_integral();
            int dy = mFlow.pixel_flow_y_integral();
            final boolean bReversed = true;
            if (bReversed) {
                // we're running the robot backwards, so camera is on the front and dx,dy are negated
                dx = -dx;
                dy = -dy;
            }

            // get bearing from IMU gyro to compare to camera heading (appears more reliable)
            double imuBearingDeg = mGyro.getHeading();

            // current ratbot PX4Flow setup:
            // camera board N" from ground with 4mm f/1.2 lens
            // yields 10000 X-counts per 29"
            double scale = 29.0/10000.0;

            // update accumulated field position - note args to move(right,forward) --
            // on ratbot, camera is mounted such that forward is +x and right is +y
            mPosInt.move(dy*scale, dx*scale, imuBearingDeg);

            if (mOpMode != null)
                mOpMode.telemetry.addData("PX4FlowGyroPosInt position", String.format("%.2f", this.getX())+", " + String.format("%.2f", this.getY()));

            return true;
        }

        public HeadingSensor getGyro() {
            return mGyro;
        }
    }

    // return done when we're within tolerance distance of target position
    class PositionTerminatorStep extends AutoLib.MotorGuideStep {

        OpMode mOpMode;
        SensorLib.PositionIntegrator mPosInt;
        Position mTarget;
        double mTol;

        public PositionTerminatorStep(OpMode opmode, SensorLib.PositionIntegrator posInt, Position target, double tol) {
            mOpMode = opmode;
            mPosInt = posInt;
            mTarget = target;
            mTol = tol;
        }

        @Override
        public boolean loop() {
            super.loop();
            Position current = mPosInt.getPosition();
            double dist = Math.sqrt((mTarget.x-current.x)*(mTarget.x-current.x) + (mTarget.y-current.y)*(mTarget.y-current.y));
            if (mOpMode != null) {
                mOpMode.telemetry.addData("PositionTerminatorStep target", String.format("%.2f", mTarget.x) + ", " + String.format("%.2f", mTarget.y));
                mOpMode.telemetry.addData("PositionTerminatorStep current", String.format("%.2f", current.x) + ", " + String.format("%.2f", current.y));
                mOpMode.telemetry.addData("PositionTerminatorStep dist", String.format("%.2f", dist));
            }
            boolean bDone = (dist < mTol);
            return bDone;
        }
    }


    // guide step that uses a gyro and a position integrator to determine how to guide the robot to the target
    class GyroPosIntGuideStep extends AutoLib.GyroGuideStep {

        OpMode mOpMode;
        Position mTarget;
        PX4FlowGyroPosInt mPosInt;

        public GyroPosIntGuideStep(OpMode opmode, PX4FlowGyroPosInt posInt, Position target,
                                   SensorLib.PID pid, ArrayList<AutoLib.SetPower> motorsteps, float power) {
            super(opmode, 0, posInt.getGyro(), pid, motorsteps, power);
            mOpMode = opmode;
            mTarget = target;
            mPosInt = posInt;
        }

        public boolean loop() {
            // run the EncoderGyroPosInt to update its position based on encoders and gyro
            mPosInt.loop();

            // update the GyroGuideStep heading to continue heading for the target
            super.setHeading((float) HeadingToTarget(mTarget, mPosInt.getPosition()));

            // run the underlying GyroGuideStep and return what it returns for "done" -
            // currently, it leaves it up to the terminating step to end the Step
            return super.loop();
        }

        private double HeadingToTarget(Position target, Position current) {
            double headingXrad = Math.atan2((target.y - current.y), (target.x - current.x));  // pos CCW from X-axis
            double headingYdeg = SensorLib.Utils.wrapAngle(Math.toDegrees(headingXrad) - 90.0);
            if (mOpMode != null) {
                mOpMode.telemetry.addData("GyroPosIntGuideStep.HeadingToTarget target", String.format("%.2f", target.x) + ", " + String.format("%.2f", target.y));
                mOpMode.telemetry.addData("GyroPosIntGuideStep.HeadingToTarget current", String.format("%.2f", current.x) + ", " + String.format("%.2f", current.y));
                mOpMode.telemetry.addData("GyroPosIntGuideStep.HeadingToTarget heading", String.format("%.2f", headingYdeg));
            }
            return headingYdeg;
        }
    }

    // Step: drive to given absolute field position using given EncoderGyroPosInt
    class PosIntDriveToStep extends AutoLib.GuidedTerminatedDriveStep {

        OpMode mOpMode;
        PX4FlowGyroPosInt mPosInt;
        Position mTarget;
        AutoLib.GyroGuideStep mGuideStep;
        PositionTerminatorStep mTerminatorStep;

        public PosIntDriveToStep(OpMode opmode, PX4FlowGyroPosInt posInt, DcMotor[] motors,
                                 float power, SensorLib.PID pid, Position target, double tolerance, boolean stop)
        {
            super(opmode,
                    new GyroPosIntGuideStep(opmode, posInt, target, pid, null, power),
                    new PositionTerminatorStep(opmode, posInt, target, tolerance),
                    motors);

            mOpMode = opmode;
            mPosInt = posInt;
            mTarget = target;
        }

    }


    AutoLib.Sequence mSequence;             // the root of the sequence tree
    boolean bDone;                          // true when the programmed sequence is done
    DcMotor mMotors[];                      // motors, some of which can be null: assumed order is fr, br, fl, bl
    BNO055IMUHeadingSensor mGyro;           // gyro to use for heading information
    boolean bSetup;                         // true when we're in "setup mode" where joysticks tweak parameters
    SensorLib.PID mPid;                     // PID controller for the sequence
    SensorLib.PIDAdjuster mPidAdjuster;     // for interactive adjustment of PID parameters
    PX4FlowGyroPosInt mPosInt;              // Encoder/gyro-based position integrator to keep track of where we are
    PX4Flow mFlow;                          // the PX4Flow camera device


    @Override
    public void init() {
        bSetup = false;      // start out in Kp/Ki setup mode
        AutoLib.HardwareFactory mf = null;
        final boolean debug = false;
        if (debug)
            mf = new AutoLib.TestHardwareFactory(this);
        else
            mf = new AutoLib.RealHardwareFactory(this);

        // get the motors: depending on the factory we created above, these may be
        // either dummy motors that just log data or real ones that drive the hardware
        // assumed order is fr, br, fl, bl
        mMotors = new DcMotor[4];
        mMotors[0] = mf.getDcMotor("fr");
        mMotors[1] = mf.getDcMotor("br");
        (mMotors[2] = mf.getDcMotor("fl")).setDirection(DcMotor.Direction.REVERSE);
        (mMotors[3] = mf.getDcMotor("bl")).setDirection(DcMotor.Direction.REVERSE);

        // get hardware IMU and wrap gyro in HeadingSensor object usable below
        mGyro = new BNO055IMUHeadingSensor(hardwareMap.get(BNO055IMU.class, "imu"));
        mGyro.init(7);  // orientation of REV hub in my ratbot
        mGyro.setDegreesPerTurn(355.0f);     // appears that's what my IMU does ...

        // create a PID controller for the sequence
        // parameters of the PID controller for this sequence - assumes 20-gear motors (fast)
        float Kp = 0.02f;        // motor power proportional term correction per degree of deviation
        float Ki = 0.025f;         // ... integrator term
        float Kd = 0;             // ... derivative term
        float KiCutoff = 10.0f;    // maximum angle error for which we update integrator
        mPid = new SensorLib.PID(Kp, Ki, Kd, KiCutoff);    // make the object that implements PID control algorithm

        // create a PID adjuster for interactive tweaking (see loop() below)
        mPidAdjuster = new SensorLib.PIDAdjuster(this, mPid, gamepad1);

        // get PX4Flow pixel-flow camera
        mFlow = hardwareMap.get(PX4Flow.class, "PX4Flow");
        mFlow.readIntegral();       // throw one frame away to flush out any accumulated gook

        // create PX4Flow/gyro-based PositionIntegrator to keep track of where we are on the field
        Position initialPosn = new Position(DistanceUnit.INCH, 0.0, 0.0, 0.0, 0);  // starting position
        mPosInt = new PX4FlowGyroPosInt(this, mFlow, mGyro, initialPosn);


        // create an autonomous sequence with the steps to drive
        // several legs of a polygonal course ---
        float movePower = 0.20f;
        float turnPower = 0.25f;

        // create the root Sequence for this autonomous OpMode
        mSequence = new AutoLib.LinearSequence();

        // add a bunch of timed "legs" to the sequence - use Gyro heading convention of positive degrees CW from initial heading
        float tol = 3.0f;   // tolerance in inches
        float timeout = 2.0f;   // seconds

        // add a bunch of position integrator "legs" to the sequence -- uses absolute field coordinate system in inches
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, movePower, mPid,
                new Position(DistanceUnit.INCH, 0, 36, 0., 0), tol, false));
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, movePower, mPid,
                new Position(DistanceUnit.INCH, 36, 36, 0., 0), tol, false));
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, movePower, mPid,                   // do this move backwards!
                new Position(DistanceUnit.INCH, 36, 0, 0., 0), tol, false));
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, movePower, mPid,
                new Position(DistanceUnit.INCH, 0, 0, 0., 0), tol, false));

        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, movePower, mPid,
                new Position(DistanceUnit.INCH, 0, 36, 0., 0), tol, false));
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, -movePower, mPid,
                new Position(DistanceUnit.INCH, 36, 36, 0., 0), tol, false));
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, -movePower, mPid,                   // do this move backwards!
                new Position(DistanceUnit.INCH, 36, 0, 0., 0), tol, false));
        mSequence.add(new PosIntDriveToStep(this, mPosInt, mMotors, movePower, mPid,
                new Position(DistanceUnit.INCH, 0, 0, 0., 0), tol, false));

        // turn to heading zero to finish up
        mSequence.add(new AutoLib.AzimuthTolerancedTurnStep(this, 0, mGyro, mPid, mMotors, turnPower, tol, timeout));
        mSequence.add(new AutoLib.MoveByTimeStep(mMotors, 0, 0, true));     // stop all motors

        // start out not-done
        bDone = false;
    }

    @Override
    public void loop() {

        if (gamepad1.y)
            bSetup = true;      // Y button: enter "setup mode" using controller inputs to set Kp and Ki
        if (gamepad1.x)
            bSetup = false;     // X button: exit "setup mode"
        if (bSetup) {           // "setup mode"
            mPidAdjuster.loop();
            return;
        }

        // until we're done, keep looping through the current Step(s)
        if (!bDone)
            bDone = mSequence.loop();       // returns true when we're done
        else
            telemetry.addData("sequence finished", "");
    }

    @Override
    public void stop() {
        super.stop();
    }
}

