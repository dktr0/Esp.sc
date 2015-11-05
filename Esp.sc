/*
Esp -- SuperCollider classes to connect with EspGrid (classes Esp and EspClock)
by David Ogborn <ogbornd@mcmaster.ca>

Installation Instructions:
1. Place this file in your SuperCollider extensions folder
2. Launch SuperCollider (or Reboot Interpreter, or Recompile Class Library)

Examples of Use:

Esp.version; // display version of this SC extension (and verify that it is installed!)
Esp.gridVersion; // see what version of EspGrid is running

// Configuration:
// Note that all of these configuration methods are persistent - generally speaking
// EspGrid/espgridd remembers them between sessions. So you would normally only use these methods
// when using EspGrid/espgridd for the first time or when making changes to the configuration

Esp.person = "David"; // set your name on the grid
Esp.person; // check your name on the grid
Esp.machine = "Macbook"; // identify the machine on the grid
Esp.broadcast = "10.0.0.7"; // change the broadcast address (if necessary)
Esp.clockMode = 5; // change the clock sync mode (see EspGrid documentation)

// Chat
// Received chat messages appear in the SuperCollider post window
Esp.chat("hi there"); // send a chat message with EspGrid

// TempoClock synchronization
TempoClock.default = EspClock.new; // make the default clock a new EspClock
TempoClock.default.start; // if the beat is paused/was-never-started, make it go
TempoClock.tempo = 1.8; // change tempo in normal SC way (all changes shared via EspGrid)
TempoClock.default.pause; // pause the beat

// END of help/documentation
*/

Esp {
	// public properties
    classvar <version; // a string describing the update-date of this class definition
	classvar <gridAddress; // string pointing to network location of EspGrid (normally loopback)
	classvar <send; // cached NetAddr for communication from SC to EspGrid
    classvar <>clockAdjust; // manual adjustment for when you have a high latency, remote EspGrid (NOT recommended)

	classvar <person;
	classvar <machine;
	classvar <broadcast;
	classvar <clockMode;
	classvar <gridVersion;

	classvar <>verbose; // set to true for detailed logging to console

	*gridAddress_ {
		|x|
		gridAddress = x;
		send = NetAddr(gridAddress,5510);
		send.sendMsg("/esp/subscribe");
	}

	*person_ { |x| send.sendMsg("/esp/person/s",x); send.sendMsg("/esp/person/q"); }
	*machine_ { |x| send.sendMsg("/esp/machine/s",x); send.sendMsg("/esp/machine/q"); }
	*broadcast_ { |x| send.sendMsg("/esp/broadcast/s",x); send.sendMsg("/esp/broadcast/q"); }
	*clockMode_ { |x| send.sendMsg("/esp/clockMode/s",x); send.sendMsg("/esp/clockMode/q"); }
	*chat { |x| send.sendMsg("/esp/chat/send",x); }

	*initClass {
		version = "5 November 2015";
		("Esp.sc: " + version).postln;
		" recommended minimum EspGrid version to use with this Esp.sc: 0.53.5".postln;
		if(Main.scVersionMajor<3 || (Main.scVersionMajor==3 && Main.scVersionMinor<7),{
			" WARNING: SuperCollider 3.7 or higher is required".postln;
		});
		verbose = false;
		Esp.gridAddress = "127.0.0.1";
		clockAdjust = 0.0;

		StartUp.add {
			OSCdef(\espChat,{ |m,t,a,p| (m[1] ++ " says: " ++ m[2]).postln; },"/esp/chat/receive").permanent_(true);
			OSCdef(\espPerson,{|m,t,a,p|person=m[1]},"/esp/person/r").permanent_(true);
			OSCdef(\espMachine,{|m,t,a,p|machine=m[1]},"/esp/machine/r").permanent_(true);
			OSCdef(\espBroadcast,{|m,t,a,p|broadcast=m[1]},"/esp/broadcast/r").permanent_(true);
			OSCdef(\espClockMode,{|m,t,a,p|clockMode=m[1];},"/esp/clockMode/r").permanent_(true);
			OSCdef(\espVersion,{|m,t,a,p|gridVersion=m[1];},"/esp/version/r").permanent_(true);
		};

		// resend subscription and query basic settings every 3 seconds in case EspGrid
		// is started later than SuperCollider, or restarted
		SkipJack.new( {
			Esp.send.sendMsg("/esp/subscribe");
			Esp.send.sendMsg("/esp/person/q");
			Esp.send.sendMsg("/esp/machine/q");
			Esp.send.sendMsg("/esp/broadcast/q");
			Esp.send.sendMsg("/esp/clockMode/q");
			Esp.send.sendMsg("/esp/version/q");
		}, 2, clock: SystemClock);

	}
}


EspClock : TempoClock {

	// private variables:
	var clockDiff; // difference between SystemClock.seconds and Main.monotonicClockTime

	// public methods:
	pause { Esp.send.sendMsg("/esp/beat/on",0); }
	start { Esp.send.sendMsg("/esp/beat/on",1); }
	tempo_ {|t| if(t<10,{Esp.send.sendMsg("/esp/beat/tempo", t * 60);},{"tempo too high".postln;});}

 	init {
		| tempo,beats,seconds,queueSize |
		super.init(0.000000001,beats,seconds,queueSize);
		permanent = true;

		if(Main.respondsTo(\monotonicClockTime),
			{
				clockDiff = Main.monotonicClockTime - SystemClock.seconds;
				// Note: what we really need is access to the logical difference between
				// SuperCollider's logical SystemClock time and the monotonicClockTime
				// but having Main.monotonicClockTime should be a big improvement for now!
			},{
				" WARNING: SuperCollider does NOT have Main.monotonicClockTime, using /esp/clock/q".postln;
				OSCdef(\espClock,
					{
						| msg,time,addr,port |
						if(Esp.verbose,{msg.postln});
						clockDiff = msg[1]+(msg[2]*0.000000001) - SystemClock.seconds;
					},
					"/esp/clock/r").permanent_(true);
				Esp.send.sendMsg("/esp/clock/q");
				SkipJack.new( {Esp.send.sendMsg("/esp/clock/q");}, 10.0, clock: SystemClock);
		});

		OSCdef(\espTempo,
			{
				| msg,time,addr,port |
				if(clockDiff.notNil,{
					var on = msg[1];
					var freq = if(on==1,msg[2]/60,0.000000001);
					var time = msg[3] + (msg[4]*0.000000001);
					var beat = msg[5];
					super.beats_((SystemClock.seconds - time + clockDiff + Esp.clockAdjust) * freq + beat);
					super.tempo_(freq);
					if(Esp.verbose,{
						msg.postln;
						[SystemClock.seconds,clockDiff,SystemClock.seconds+clockDiff].postln;
					});
				});
			},"/esp/tempo/r").permanent_(true);
        SkipJack.new( {Esp.send.sendMsg("/esp/tempo/q");}, 0.05, clock: SystemClock);
	}

}
