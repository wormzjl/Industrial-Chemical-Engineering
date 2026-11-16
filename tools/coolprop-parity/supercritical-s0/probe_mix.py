import CoolProp.CoolProp as CP
for pair,z in [("Methane&CarbonDioxide",0.5),("Methane&Ethane",0.5),("Nitrogen&Methane",0.5),("Methane&CarbonDioxide",0.25)]:
    for T in (250,300,350):
        for P in (5e6,10e6,20e6,30e6):
            st=CP.AbstractState("HEOS",pair); st.set_mole_fractions([z,1-z])
            try:
                st.update(CP.PT_INPUTS,P,T); print(pair,z,T,P/1e6,st.phase(),CP.get_phase_index('phase_twophase'),round(st.rhomolar(),3),st.Q())
            except Exception as e: print(pair,z,T,P/1e6,"ERR",str(e)[:100])
st=CP.AbstractState("HEOS","CarbonDioxide"); st.update(CP.PT_INPUTS,10e6,320); print("CO2 visc",st.viscosity(),st.rhomolar())
for f in ["n-Propane","Ethylene","n-Hexane","n-Heptane","Toluene","n-Decane","n-Dodecane"]:
    s=CP.AbstractState("HEOS",f); print(f,s.Tmin(),s.Tmax(),s.pmax(),s.T_critical(),s.p_critical(),s.acentric_factor())
