/*
Esp -- SuperCollider classes to connect with EspGrid/espgridd (classes Esp and EspClock)
by David Ogborn <ogbornd@mcmaster.ca>

Basic usage:
Launch the EspGrid or espgridd applications through your operating system
Create an EspClock and make it the default: TempoClock.default = EspClock.new;
If no one else has started the tempo: TempoClock.default.start;
Change the tempo as per normal SC practice: TempoClock.tempo = 104/60;
Chat with other ensemble members: Esp.chat("hi there");

First-time usage: If you are using EspGrid for the first time you should set your name
and machine name on the EspGrid network (EspGrid/espgridd remembers this for next time):
Esp.person = "d0kt0r0";
Esp.machine = "laptop";

Some less common but still useful usages:
Esp.start; // just start communication with EspGrid without chatting or making a clock
Esp.version; // display version of this SC extension (and verify that it is installed!)
Esp.gridVersion; // see what version of EspGrid is running
Esp.person; // see what the person name in EspGrid is
Esp.machine; // see what the
Esp.broadcast = "10.0.0.7"; // change the EspGrid broadcast address (if necessary)
Esp.broadcast; // check what the current Espgrid broadcast address is
Esp.clockMode = 5; // change the clock sync mode (see EspGrid documentation)
TempoClock.default.pause; // pause the beat
Esp.clockAdjust = 0.2; // slide synced tempo/metre forward in time
// the line above is useful when specific SC setups make sound a fixed time later than
// other synced setups, i.e. because of differences in the latencies of the audio system.
// This is typically preferable to reducing the SC server latency (because you keep the
// advantages of having a server latency). The default clockAdjust is 0.

// END of help/documentation
*/

Esp {
	// public properties
	classvar version; // a string describing the update-date of this class definition
	classvar gridAddress; // string pointing to network location of EspGrid (normally loopback)
	classvar send; // cached NetAddr for communication from SC to EspGrid
	classvar clockAdjust; // manual adjustment for when you have a high latency, remote EspGrid (NOT recommended)
	classvar person;
	classvar broadcast;
	classvar clockMode;
	classvar gridVersion;
	classvar started;
	classvar verbose; // set to true for detailed logging to console

	*initClass {
		started = false;
		version = "12February2016a";
		verbose = false;
		gridAddress = "127.0.0.1";
		clockAdjust = 0.0;
	}

	*startIfNecessary {
		if(not(started),{Esp.start});
	}

	*start {
		started = true; // this has to be done first to avoid potential for infinite recursion
		("starting Esp.sc, version" + version + "(for EspGrid 0.57.0 or higher)").postln;
		if(Main.scVersionMajor<3 || (Main.scVersionMajor==3 && Main.scVersionMinor<7),{
			" WARNING: SuperCollider 3.7 or higher is required by Esp.sc".postln;
		});

		OSCdef(\espChat,{ |m,t,a,p| (m[1] ++ " says: " ++ m[2]).postln; },"/esp/chat/receive").permanent_(true);
		OSCdef(\espPerson,{|m,t,a,p|person=m[1];},"/esp/person/r").permanent_(true);
		OSCdef(\espBroadcast,{|m,t,a,p|broadcast=m[1]},"/esp/broadcast/r").permanent_(true);
		OSCdef(\espClockMode,{|m,t,a,p|clockMode=m[1];},"/esp/clockMode/r").permanent_(true);
		OSCdef(\espVersion,{|m,t,a,p|gridVersion=m[1];},"/esp/version/r").permanent_(true);

		// resend subscription and query basic settings every 3 seconds in case EspGrid
		// is started later than SuperCollider, or restarted
		Esp.gridAddress = gridAddress; // called to create initial NetAddr
		SkipJack.new( {
			Esp.send.sendMsg("/esp/subscribe");
			Esp.send.sendMsg("/esp/person/q");
			Esp.send.sendMsg("/esp/broadcast/q");
			Esp.send.sendMsg("/esp/clockMode/q");
			Esp.send.sendMsg("/esp/version/q");
		}, 2, clock: SystemClock);
	}

	*gridAddress_ {
		|x|
		gridAddress = x;
		send = NetAddr(gridAddress,5510);
		send.sendMsg("/esp/subscribe");
		Esp.startIfNecessary;
	}

	// all setters and getters call Esp.startIfNecessary
	// so that any use of the Esp class has an implicit Esp.start

	*gridAddress { Esp.startIfNecessary; ^gridAddress; }
	*send { Esp.startIfNecessary; ^send; }
	*clockAdjust { Esp.startIfNecessary; ^clockAdjust; }
	*clockAdjust_ { |x| Esp.startIfNecessary; clockAdjust = x; }
	*started { Esp.startIfNecessary; ^started; }
	*person { Esp.startIfNecessary; ^person; }
	*person_ { |x| Esp.startIfNecessary; send.sendMsg("/esp/person/s",x); send.sendMsg("/esp/person/q"); }
	*broadcast { Esp.startIfNecessary; ^broadcast; }
	*broadcast_ { |x| Esp.startIfNecessary; send.sendMsg("/esp/broadcast/s",x); send.sendMsg("/esp/broadcast/q"); }
	*clockMode { Esp.startIfNecessary; ^clockMode; }
	*clockMode_ { |x| Esp.startIfNecessary; send.sendMsg("/esp/clockMode/s",x); send.sendMsg("/esp/clockMode/q"); }
	*version { Esp.startIfNecessary; ^version; }
	*gridVersion { Esp.startIfNecessary; ^gridVersion; }
	*chat { |x| Esp.startIfNecessary; send.sendMsg("/esp/chat/send",x); }
	*verbose { Esp.startIfNecessary; ^verbose; }
	*verbose_ { |x| Esp.startIfNecessary; verbose=x; }
}


EspClock : TempoClock {

	// public variables:
	var <adjustments; // number of times tempo messages have been received from EspGrid

	// public methods:
	pause { Esp.send.sendMsg("/esp/beat/on",0); }
	start { Esp.send.sendMsg("/esp/beat/on",1); }
	tempo_ {|t| if(t<10,{Esp.send.sendMsg("/esp/beat/tempo", t * 60);},{"tempo too high".postln;});}

 	init {
		| tempo,beats,seconds,queueSize |
		super.init(0.000000001,beats,seconds,queueSize);
		permanent = true;
		adjustments = 0;

		OSCdef(\espTempo,
			{
				| msg,t,addr,port |
				var on = msg[1];
				var freq = if(on==1,msg[2]/60,0.000000001);
				var time = msg[3] + (msg[4]*0.000000001);
				var beat = msg[5];
				var target = (Date.getDate.rawSeconds + Esp.clockAdjust - time) * freq + beat;
				var adjust = target - super.beats;
				if((adjustments>10) && (adjust>1), {
					"warning: EspClock adjustment greater than one beat".postln;
					target = super.beats + 1;
				});
				super.beats_(target);
				super.tempo_(freq);
				adjustments = adjustments + 1;
				if(Esp.verbose,{msg.postln});
			},"/esp/tempo/r").permanent_(true);
        SkipJack.new( {Esp.send.sendMsg("/esp/tempo/q");}, 0.05, clock: SystemClock);
	}

}
