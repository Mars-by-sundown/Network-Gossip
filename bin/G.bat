start cmd /c javac Gossip.java
PAUSE 2
for /l %%x in (0, 1, 6) do (
   start cmd /k java Gossip %%x
)