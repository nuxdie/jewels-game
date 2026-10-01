// Renders the effects in android/assets/sfx.txt to WAV files, the way the game plays them, so they can be
// auditioned without a phone. See README.md.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <math.h>
#include <libopenmpt/libopenmpt.h>
#include <libopenmpt/libopenmpt_ext.h>
typedef struct {char fx[64]; int ms, ins, note, hold; double vol, pan;} Ev;
static int parse_note(const char*s){ static const char*n[]={"C-","C#","D-","D#","E-","F-","F#","G-","G#","A-","A#","B-"}; for(int i=0;i<12;i++) if(!strncasecmp(s,n[i],2)) return i+12*atoi(s+2); return -1;}
int main(int c,char**v){ if(c<4){fprintf(stderr,"render mod defs outdir [gain_mb]\n");return 1;}
 FILE*f=fopen(v[1],"rb");fseek(f,0,2);long n=ftell(f);rewind(f);char*b=malloc(n);if(!fread(b,1,n,f))return 1;
 openmpt_module_ext*x=openmpt_module_ext_create_from_memory(b,n,0,0,0,0,0,0,0); openmpt_module*m=openmpt_module_ext_get_module(x);
 openmpt_module_ext_interface_interactive it; openmpt_module_ext_get_interface(x,LIBOPENMPT_EXT_C_INTERFACE_INTERACTIVE,&it,sizeof it);
 openmpt_module_ext_interface_interactive2 it2; openmpt_module_ext_get_interface(x,LIBOPENMPT_EXT_C_INTERFACE_INTERACTIVE2,&it2,sizeof it2);
 openmpt_module_set_render_param(m,OPENMPT_MODULE_RENDER_MASTERGAIN_MILLIBEL,c>4?atoi(v[4]):1000);
 openmpt_module_set_render_param(m,OPENMPT_MODULE_RENDER_INTERPOLATIONFILTER_LENGTH,8);
 int ni=openmpt_module_get_num_instruments(m);
 static Ev ev[4096]; int ne=0; char line[512]; FILE*d=fopen(v[2],"r");
 while(fgets(line,sizeof line,d)){ char*h=strchr(line,'#'); if(h&&(h==line||h[-1]==' '||h[-1]=='\t')&&!(h[1]>='0'&&h[1]<='9')) *h=0;
   char fx[64],in[64],nt[8]; Ev e; if(sscanf(line,"%63s %d %63s %7s %lf %lf %d",fx,&e.ms,in,nt,&e.vol,&e.pan,&e.hold)!=7) continue;
   strcpy(e.fx,fx); e.note=parse_note(nt); e.ins=-1;
   if(in[0]=='#') e.ins=atoi(in+1)-1; else for(int i=0;i<ni;i++){ char nm[64]; snprintf(nm,64,"%s",openmpt_module_get_instrument_name(m,i)); for(char*p=nm;*p;p++) if(*p==' ')*p='_'; if(!strcasecmp(nm,in)){e.ins=i;break;} }
   if(e.ins<0||e.note<0){fprintf(stderr,"bad line: %s",line);return 1;} ev[ne++]=e; }
 int rate=44100; float*buf=malloc(sizeof(float)*2*rate*12);
 for(int i=0;i<ne;i++){ if(i>0&&!strcmp(ev[i].fx,ev[i-1].fx)) continue;
   const char*name=ev[i].fx; int j=i; while(j<ne&&!strcmp(ev[j].fx,name)) j++;
   openmpt_module_set_position_order_row(m,179,1); it.set_current_speed(x,65535);
   int tot=0; for(int k=i;k<j;k++){int e=(ev[k].ms+ev[k].hold)*rate/1000; if(e>tot)tot=e;} tot+=rate*4; if(tot>rate*12) tot=rate*12;
   int chs[512]; int got=0, last=0;
   while(got<tot){ int nextT=tot; for(int k=i;k<j;k++){int a=ev[k].ms*rate/1000,o=(ev[k].ms+ev[k].hold)*rate/1000; if(a>=got&&a<nextT)nextT=a; if(o>=got&&o<nextT)nextT=o;}
     for(int k=i;k<j;k++){ if(ev[k].ms*rate/1000==got) chs[k-i]=it.play_note(x,ev[k].ins,ev[k].note,ev[k].vol,ev[k].pan); }
     for(int k=i;k<j;k++){ if((ev[k].ms+ev[k].hold)*rate/1000==got && chs[k-i]>=0) it2.note_off(x,chs[k-i]); }
     if(nextT==got){ nextT=got+1; }
     int want=nextT-got; if(want>1024) want=1024;
     int r=openmpt_module_read_interleaved_float_stereo(m,rate,want,buf+2*got); if(!r)break; got+=r; }
   for(int s=0;s<got;s++) if(fabsf(buf[2*s])>.002f||fabsf(buf[2*s+1])>.002f) last=s;
   int len=last+rate/50; if(len>got) len=got;
   char p[512]; snprintf(p,512,"%s/%s.wav",v[3],name); FILE*o=fopen(p,"wb");
   int bytes=len*4; int hdr[]={0x46464952,36+bytes,0x45564157,0x20746d66,16,0x00020001,rate,rate*4,0x00100004,0x61746164,bytes}; fwrite(hdr,4,11,o);
   for(int s=0;s<len*2;s++){ float a=buf[s]; if(a>1)a=1; if(a<-1)a=-1; short q=(short)lrintf(a*32767); fwrite(&q,2,1,o);} fclose(o);
 }
 return 0;}
