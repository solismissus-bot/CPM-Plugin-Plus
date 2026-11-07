# CPM Plugin+: como usar tudo

## ⌨️ Atalhos de teclado

| Atalho | Ferramenta |
|---|---|
| **Ctrl + Alt + A** | Ajustes do modelo |
| **Ctrl + Alt + B** | Pose intermediária (tween) |
| **Ctrl + Alt + D** | Atrasar / adiantar movimento |
| **Ctrl + Alt + F** | Copiar movimento de (Copy Transform) |
| **Ctrl + Alt + G** | Grudar na parte do corpo |
| **Ctrl + Alt + H** | Mostrar/esconder nesta animação |
| **Ctrl + Alt + I** | Importar animações de outro .cpmproject |
| **Ctrl + Alt + J** | Levar pro jogo |
| **Ctrl + Alt + K** | Ver esqueleto (liga/desliga) |
| **Ctrl + Alt + L** | Fechar o loop |
| **Ctrl + Alt + M** | Espelhar pro outro lado (abre a janela) |
| **Ctrl + Alt + Shift + M** | Espelhar pro outro lado + animações, direto (sem janela) |
| **Ctrl + Alt + N** | Fazer onda |
| **Ctrl + Alt + O** | Otimizar animação |
| **Ctrl + Alt + P** | Marcar pose aqui |
| **Ctrl + Alt + R** | Criar cópia espelhada da animação |
| **Ctrl + Alt + U** | 🆕 Física: seguir o movimento |
| **Ctrl + Alt + V** | Suavizar movimento |
| **Ctrl + Alt + X** | Exagerar ou suavizar movimento |
| **Ctrl + Alt + Y** | 🆕 Ajuste relativo (somar em todas as keys) |
| **Ctrl + Alt + Z** | 🆕 Editar keys em várias animações |
| **Ctrl + Alt + Shift + A** | 🆕 Camada de ajuste: criar / editar (aperte de novo pra voltar pra original) |
| **Ctrl + Alt + Shift + B** | Colar pose no outro lado (espelhada) |
| **Ctrl + Alt + Shift + C** | Copiar pose das partes selecionadas |
| **Ctrl + Alt + Shift + K** | 🆕 Conferir movimento |
| **Ctrl + Alt + Shift + N** | 🆕 Nova animação pelo que você quer |
| **Ctrl + Alt + Shift + R** | Espelhar pose |
| **Ctrl + Alt + Shift + V** | Colar pose |

🆕 = novidade recente (beta28: ferramentas de animação; beta29-30: arte e pincéis; beta31: tudo de pintura no painel CPM+ Pintar; beta32: pincéis HD e Esfumar ao lado da Borracha, seção 5; beta33: painel CPM+ Modelar com 30 ferramentas de modelagem e a Biblioteca de peças, seção 4; beta34: rig e animação avançada na seção 3 (Reagir ao jogo, Acompanhar parte do corpo, Curvar corrente, Dividir animação grande, Chão andando) e, na seção 4, Juntar outro modelo, Arrumar UV automático, Encaixe magnético e peças novas na biblioteca). Pra trocar ou criar atalho pra qualquer outra ferramenta: **Arquivo > Configurações > Atalhos**, categoria **Ferramentas**, procure pelo nome. Se um atalho não funcionar, veja nessa mesma tela se outra ferramenta (de outro plugin, por exemplo) usa a mesma tecla e troque uma delas.

Quase tudo fica no menu **CPM+** (barra de cima do Blockbench), dividido em: **Ajustes do modelo, Animação, Modelagem, Textura, Otimizar, Prévia, Ajuda**. Muita coisa também aparece no **clique direito** numa pasta, num cubo ou numa animação.

Regras gerais:
- **Tudo desfaz com um Ctrl+Z** (exceto onde está escrito "Desfazer no aviso").
- As ferramentas de animação pedem o **modo Animar** com uma animação selecionada.
- Os avisos do export **nunca bloqueiam**: você pode exportar mesmo assim.
- Teste sempre em uma **cópia** do seu modelo.

---

## 1. Levar pro jogo (direto no menu CPM+)

| Item | Como usar |
|---|---|
| **Levar pro jogo...** | Explica e oferece os caminhos: skin com o modelo dentro (funciona até em servidor sem o mod), arquivo .cpmmodel ou .cpmproject, e quem vai conseguir ver. Siga o passo a passo da janela. |
| **Exportar modelo (.cpmmodel)** | Gera o arquivo da lista "Modelos" do jogo. Ponha em `.minecraft/player_models` e use o comando que aparece (`/cpmclient set_model ...`). Também está em Arquivo > Exportar. |
| **Testar no jogo agora** | Faz uma cópia de teste e mostra o que fazer no jogo (T, Ctrl+V, Enter). |
| **Conferir uma skin...** | Abre uma skin .png e diz se ela tem modelo do CPM dentro, se está inteira e o tamanho. |
| **Quem pode ver meu modelo?** | Mostra se o modelo carrega pros outros jogadores em cada nível de segurança do CPM, e por quê (cubos, textura, tamanho do link). Só informa. |

## 2. Ajustes do modelo (CPM+ > Ajustes do modelo)

| Item | Como usar |
|---|---|
| **Ajustes do modelo…** | Braço **Steve (4 px) ou Alex (3 px)**. Marque "ajustar a largura dos braços" pra ele mexer nos cubos junto. Também: esconder a cabeça com crânio, tirar deslocamento da armadura/cama, brilho visível com invisibilidade. |
| **Nome, descrição e ícone…** | Nome e descrição que aparecem no jogo, ícone (redimensiona sozinho) e **quem pode copiar** o modelo (incluindo travar na sua conta). |
| **Tamanho e atributos…** | Escala do jogador, altura dos olhos, alcance etc. Valores diferentes de 1 só funcionam em servidor se o staff liberar; a janela mostra os comandos pro staff. |
| **ID do modelo** (botão dentro de Ajustes do modelo) | **Gerar** cria o ID; **Copiar** copia. Sem ID, o jogo não lembra as opções salvas ("Salvar entre partidas") nem os atalhos de quem usa o modelo. Use o mesmo ID em todas as versões. Trocar o ID tem "Desfazer no aviso". |
| **Camadas da skin pras animações (avançado)...** | Escolha quais camadas da skin o CPM usa pra mandar suas animações pros outros jogadores (quando o export reclamar de falta de espaço). |

## 3. Animação (CPM+ > Animação)

> **Aditiva ou não aditiva?** (Propriedades da animação > Aditiva) **Aditiva** = soma com o Minecraft: braços e pernas continuam balançando e a cabeça segue o mouse. **Não aditiva** = sua animação manda nas partes do corpo que ela mexe: o Minecraft para de balançá-las, e se você animar a cabeça ela para de seguir o mouse. Na dúvida, abra o **Raio-X** (seção "Conferir e arrumar"): ele mostra parte por parte o que acontece no jogo. O plugin também avisa na hora quando uma edição faz a cabeça parar de olhar pro mouse ou deixa um braço/perna balançando sem querer (clique no aviso pra abrir o Raio-X).

**🆕 Começar fácil**
| Item | Como usar |
|---|---|
| **Nova animação pelo que você quer...** (Ctrl+Alt+Shift+N) | Escolha o objetivo: algo mexendo o tempo todo (cauda, orelha, respiração), enfeitar o andar, trocar o andar inteiro, enfeitar o correr, emote/gesto (botão na roda de gestos), liga/desliga (asas abertas, chapéu), pose personalizada (sentar, deitar) ou peças que seguem pra onde a cabeça olha. A animação já nasce com o tipo do CPM, o loop e o "aditiva" certos, e a janela explica quando ela toca no jogo. Depois é só animar. |
| **Gerar andar / correr (2 ou 4 patas)...** | Cria o ciclo pronto em loop: pernas alternadas e braços opostos (ou trote de 4 patas, com as diagonais juntas), joelho/cotovelo dobrando (se o membro tiver dobra, veja "Membros dobráveis") e o corpo subindo e descendo. Escolha andando ou correndo, a duração do passo e quanto balança. Ele substitui o balanço do Minecraft nas pernas e braços; o passo tem sempre a mesma cadência (é o jeito de ter joelho/cotovelo no tempo certo). |
| **Kit de rosto (expressões, boca, olhos)...** | Monte o rosto em grupos (um grupo por expressão, um grupo de boca aberta, as pupilas) e escolha na janela. Cada expressão vira um botão liga/desliga que mostra aquele rosto e esconde os outros; a boca abre quando o jogador fala no chat de voz; os olhos deslizam conforme a cabeça gira. |
| **Testar no editor do CPM (aba Testar)** (também no clique direito da animação) | Abre o projeto no editor do CPM direto em Animação > Testar, com as animações do Minecraft ligadas e a sua animação escolhida: você vê como fica no jogo (balanço, olhar, pose) antes de exportar. |
| **Ajuda: vi isso num vídeo...** | Quase todo tutorial de CPM é gravado no editor do próprio CPM. A janela tem uma tabela "no vídeo" → "aqui": adicionar animação, tipo, aditiva, prioridade, quadros, interpolação, aba Testar, Copy Transform, esconder peça, nome do gesto (#), exportar, duplicar, loop e animação por valor. |

**Criar e gerar**
| Item | Como usar |
|---|---|
| **🆕 Física: seguir o movimento...** (Ctrl+Alt+U, também no clique direito da pasta ou da linha da timeline) | No modo Animar, na animação que as peças devem acompanhar (ex.: andar), selecione rabo, orelhas, cabelo, capa... Escolha o tipo de peça (rabo, orelha, cabelo, capa, antena, pena ou personalizado) e ajuste mole/duro, quique, peso, ângulo máximo, ponta mais solta e força. A prévia mostra na hora (desligue e ligue pra comparar). No OK ele cria a camada aditiva "nome (física)", que toca junto no jogo e fecha o loop sem emenda. Selecione a camada e abra de novo pra refazer com outros valores. |
| **Gerar movimento pronto...** | Escolha a peça e o movimento (respirar, flutuar, bater asas, balançar cauda, pêndulo, tremer, girar sem parar), se vai numa animação nova ou na atual, duração e ciclos. |
| **Gerar piscada...** | Escolha o grupo de olho aberto e o de olho fechado; intervalo regular ou aleatório. |
| **Criar seletor de cor...** | Selecione as peças: cria um controle deslizante na roda de gestos que troca a cor delas no jogo. |
| **Fazer onda…** | Selecione a primeira peça de uma corrente (rabo, cabelo, cachecol). **Onda nova**: eixo, força, quantidade e atraso. **Seguir a primeira peça**: anime só a primeira e as outras repetem com atraso. Também no clique direito da pasta (modo Animar). |
| **Partículas com sua imagem...** | Partículas falsas (peças animadas com a sua imagem). Escolha a imagem (pronta ou seu PNG), o estilo (explosão, órbita, chuva, brilhos, fumaça, atrás de você), de onde sai e quando aparece (sempre, andando, num gesto). O contador mostra o peso antes de criar. |
| **Soltar partículas daqui...** (clique direito numa pasta) | O mesmo, saindo da pasta escolhida (ex.: boca do dragão). |
| **Editar partículas...** | Selecione o grupo das partículas: muda as opções e refaz tudo. |
| **Animar bloco por bloco** (liga/desliga, fica lembrado) | No modo Animar, clique num bloquinho que divide o grupo com outros (ex.: um bloco da asa), **no modelo ou na lista de peças**: ele ganha **um osso só dele na hora**, com o pivô onde encosta no resto, e já fica selecionado pra animar. Não precisa separar grupo por grupo antes. Com a opção ligada, os bloquinhos aparecem na lista também no modo Animar. Nada sai do lugar, Ctrl+Z desfaz, e clicar de novo no mesmo bloco só seleciona o osso dele. Desligado, tudo volta ao normal do Blockbench (o clique pega o grupo inteiro). |
| **Lista só com grupos (no modo Animar)** (liga/desliga) | No modo Animar a lista de peças mostra só os grupos (ossos), sem os bloquinhos. O Blockbench 5 já faz isso sozinho; a opção serve pra quem ainda vê os blocos na lista. No modo Editar aparece tudo. |

**🆕 Rig e animação avançada**
| Item | Como usar |
|---|---|
| **Reagir ao jogo...** (também no clique direito da pasta, da animação ou da linha da timeline) | Selecione as peças e escolha o que manda nelas: a cabeça virando pros lados ou pra cima e pra baixo, vida, fome, ar, luz, voz, soco, arco ou besta (cada mão), ou um slider (um que já existe ou um novo). Receitas prontas: **pescoço segue o olhar** (dividido entre os ossos), **cabelo e orelhas pendurados** (continuam pra baixo quando a cabeça gira), **asas caem com pouca vida**, **brilhar no escuro** e **Minha pose** (o começo da linha do tempo é o mínimo e o fim é o máximo). **Simular** mostra na tela. **Criar**: funciona sozinho no jogo, sem botão. Com a animação criada selecionada, abra de novo pra **Usar a pose atual como mínimo / meio / máximo**. A janela mostra quantos botões o modelo já usa (o CPM aceita até 253). |
| **Acompanhar parte do corpo...** (também em CPM+ > Modelagem e no clique direito da pasta) | A peça copia o movimento de uma parte do corpo ao vivo no jogo: **saia** (gira no quadril), **ombreira**, **pernas extras** (alternando da frente pra trás, no ritmo das pernas do jogador), **asa no élitro**, **casaco na capa**, **engrenagem** (gira o dobro) ou do seu jeito. Escolha **Quanto** e a **Direção**, confira com **Simular passo** e clique **Acompanhar**. Parado, nada sai do lugar. A asa no élitro só mexe com o élitro equipado, e pata extra varre pros lados mas não levanta. |
| **Curvar corrente / abrir em leque...** (também no clique direito da pasta ou da linha da timeline) | No modo Animar, selecione o primeiro osso de cada corrente (base do rabo, de cada dedo, de cada mecha). Controles: **curvar**, **dobrar pro lado** e **abrir em leque** (as correntes se afastam umas das outras); em *Mais opções*: torcer, onda em S e quanto muda da base até a ponta. A prévia mostra enquanto você mexe. **Aplicar** cria as keys no tempo atual, ou de uma vez "da pose de agora até a curvada" em N quadros. Um Ctrl+Z desfaz. |
| **Dividir animação grande (mais de 255 partes)...** (clique direito na animação, ou o botão **Dividir** no aviso do export) | O CPM guarda no máximo 255 partes mexendo por animação. A animação vira "Nome", "Nome#b", "Nome#c"... que tocam juntas no mesmo botão, cada corrente de ossos inteira num pedaço só. Ctrl+Z desfaz. Não vale pra pose personalizada (o CPM criaria um botão a mais) e não divide pelo tempo. |

**Mexer no movimento**
| Item | Como usar |
|---|---|
| **Pose intermediária (tween)...** (Ctrl+Alt+B, também no clique direito da pasta ou da linha da timeline) | Cria a pose no tempo atual entre a key anterior e a próxima: arraste de -100 (igual à anterior) a +100 (igual à próxima); passando de ±100 ela passa do ponto (até 150). Ou **Puxar pro repouso** (0% = pose de agora, 100% = pose normal). Vale pras peças selecionadas (com as de dentro, se quiser) ou, sem seleção, pra todas que se mexem. Num loop, antes da primeira key vem a última. Mostra no modelo enquanto você arrasta; só rotação, posição e tamanho (cor e visibilidade nunca). |
| **Animação rápida** (submenu) | Inverter, mudar velocidade, deixar tudo suave ou tudo reto. |
| **Exagerar ou suavizar movimento...** | 0% a 300% de quanto as peças se mexem. |
| **Marcar pose aqui...** | Cria um keyframe em todas as peças animadas no tempo atual, guardando a pose. |
| **Copiar pose das partes** (Ctrl+Alt+Shift+C, também no clique direito da pasta ou da linha da timeline) | Igual o “Copiar pose da parte” do CPM, mas **várias de uma vez**: guarda a pose de agora das partes selecionadas e de tudo dentro delas (nada selecionado = o modelo todo). |
| **Colar pose** (Ctrl+Alt+Shift+V) | Cola no tempo atual, em qualquer animação. Copiou UMA pasta (ex.: asa esquerda) e selecionou OUTRA (asa direita)? Cola nela e nas partes de dentro. |
| **Colar pose no outro lado (espelhada)** (Ctrl+Alt+Shift+B) | Cola nas partes do outro lado, já espelhada. Ex.: copie a asa esquerda e cole espelhado: a direita fica igual, do jeito certo. Acha o par mesmo se os cubos tiverem nomes diferentes (cube114 / cube58), pela posição na asa. |
| **Espelhar pose** (Ctrl+Alt+Shift+R) | Espelha a pose de agora: selecione as duas asas e elas trocam de lado; uma parte sozinha espelha no lugar (igual o “Espelhar pose da parte” do CPM). |
| **Fechar o loop (sem pulo no fim)** | Cada peça termina exatamente onde começa. |
| **Atrasar / adiantar movimento...** | Selecione peças (asa, rabo) e desloque o movimento no tempo; o loop continua fechado. |
| **Suavizar movimento (começa/termina devagar)...** | Selecione 2 ou mais keyframes do mesmo canal e escolha a curva. Se estiverem colados, aumente o fps. |
| **Girar/mover o corpo inteiro…** | Gira ou move o corpo todo numa pose; cabeça, braços e pernas acompanham. |
| **Criar cópia espelhada...** (também no clique direito da animação) | Cria uma animação nova com esquerda e direita trocadas. |
| **Transformar fórmulas em keyframes...** | Troca fórmulas Molang por keyframes, pra sair igual no jogo. |
| **🆕 Ajuste relativo (somar em todas as keys)...** (Ctrl+Alt+Y, também no clique direito da pasta ou da linha da timeline) | Selecione as peças (nada selecionado = todas as animadas) e digite a mudança: girar X/Y/Z, mover X/Y/Z, tamanho. A MESMA mudança entra em todas as keys, então a pose inteira desloca e o movimento continua igual (ex.: braço 10° mais aberto na animação toda). **Onde**: animação inteira, do playhead pra frente, do começo até o playhead ou entre dois tempos (com borda suave, sem degrau). **Em quais animações**: só nesta ou em todas que mexem nessas peças. Com "Ver na tela enquanto ajusto" você confere antes de aplicar. |
| **🆕 Camada de ajuste: criar / editar** (Ctrl+Alt+Shift+A, também no clique direito da animação) | Cria "nome (ajuste)", uma camada aditiva por cima da animação: corrija nela sem mexer nas keys originais (a original continua tocando junto, no Blockbench e no jogo). Apertar de novo volta pra original. **Camada de ajuste: força...** aumenta ou diminui tudo o que ela faz (0% a 150%). **Camada de ajuste: fundir na animação...** soma a camada nas keys da original e apaga a camada. |
| **🆕 Editar keys em várias animações...** (Ctrl+Alt+Z) | Marque as animações e escolha: mudar a velocidade (duração), mais forte / mais fraco, trocar a peça (as keys de uma peça vão pra outra), copiar a trilha de uma peça desta animação pras outras (pode esticar pra ocupar a animação inteira) ou apagar a trilha de uma peça. Um Ctrl+Z desfaz tudo. |
| **🆕 Inserir clipe (sequenciador)...** | Põe outra animação dentro desta, a partir do playhead: quantas vezes, velocidade, ao contrário, empurrar o resto pra frente (ou substituir o trecho) e bordas suaves na entrada e na saída. |
| **🆕 Curvas que não passam do ponto** | Com keys selecionadas (ou peças selecionadas: todas as keys delas): vira curva suave que nunca passa do valor das vizinhas, então pico e vale ficam retos (sem o "quique" do suave comum). |
| **🆕 Esticar / achatar (mantém o volume)...** (também no clique direito da linha da timeline) | Cria uma key de tamanho no playhead nas peças selecionadas: estica num eixo e afina os outros dois na medida certa, sem a peça ficar maior ou menor. Acima de 100% estica, abaixo achata. |
| **🆕 Girar em volta de outro ponto (pivô temporário)...** (também no clique direito da pasta) | Gira as peças selecionadas em volta do pivô de OUTRA peça, sem mudar pivô nenhum: cria as keys de rotação e posição certas no playhead. |
| **🆕 Takes (versões da animação)...** (também no clique direito da animação) | **Salvar take** guarda a animação como está; teste outra ideia à vontade e depois **Restaurar** o take ou **Misturar** com ele (quanto %). Ficam salvos no projeto e não vão pro jogo. |

**🆕 Entrada, saída e trocas de pose**
| Item | Como usar |
|---|---|
| **Entrada e saída suaves...** (também no clique direito da animação) | O CPM troca de pose seco, sem misturar. Numa animação em loop (andar, sentar, pose personalizada...), crie a **entrada** (toca 1x antes do loop: vai da pose de descanso, ou do começo de outra animação, até a primeira pose) e/ou a **saída** (toca 1x depois, de volta). **Terminar a volta antes de sair** liga o "deve terminar" do CPM, pra saída começar da pose certa. Rode **Fechar o loop** antes, se o fim do loop não encontra o começo. |
| **Relatório de estalos (trocas de pose)...** | Lista as peças que dão um pulo grande quando o jogo troca de pose (parado → andando, andando → correndo, agachar...), com o pior caso e a média. **Criar entrada suave** resolve direto. Também aparece como aviso no export. |

**🆕 Ver melhor** (também em CPM+ > Prévia; só mudam a tela, nunca a animação nem o jogo)
| Item | Como usar |
|---|---|
| **Rastro das pontas** (liga/desliga) | Desenha o caminho que a PONTA de cada peça selecionada faz na animação (o rastro do Blockbench só segue o pivô). Cor = velocidade (azul devagar, vermelho rápido); cada pontinho é um quadro que vai pro jogo; clique num pontinho pra ir pra ele. |
| **Prévia em degrau (blocking)** (liga/desliga) | A tela segura cada pose até a próxima key, pra conferir só as poses-chave, sem o meio-termo. |
| **Silhueta** (liga/desliga) | Pinta o modelo inteiro de escuro pra ler a pose pelo contorno. |
| 🆕 **Chão andando (ver se o pé escorrega)** (liga/desliga no menu Animação do Blockbench e em CPM+ > Animação) | Um chão quadriculado anda pra trás na velocidade do Minecraft: andando, correndo ou agachado, conforme a animação (ou escolha em **Velocidade do chão...**). O pé que está apoiado tem que andar junto com os quadrados; se ele desliza, o passo vai patinar no jogo. Só aparece no modo Animar e não muda nada no projeto. |
| **Clicar de novo pega a peça de trás** (liga/desliga, vem ligado) | Clicou e pegou a peça da frente? Clique de novo no mesmo lugar: seleciona a que está atrás, e assim por diante. |

**Mostrar, esconder e reagir**
| Item | Como usar |
|---|---|
| **Mostrar/esconder nesta animação…** | Selecione peças: elas aparecem ou somem só nessa animação (ou num trecho). |
| **Reações automáticas…** | "Quando [capacete, elytra, dano...] então esconder/mostrar estas peças". |
| **Criar forma com movimento próprio…** | A peça anda/corre diferente quando um botão (camada) está ligado. |
| **Juntar no mesmo gesto... / Separar deste gesto...** | Gesto em várias partes (regra "Nome#parte" do CPM): várias animações tocam como um gesto só. |

**Conferir e arrumar**
| Item | Como usar |
|---|---|
| **🆕 Conferir movimento...** (Ctrl+Alt+Shift+K, também no clique direito da animação) | Procura o que costuma ficar feio no jogo: pico de 1 quadro, degrau esquecido no meio de keys suaves, pulo ou tranco na emenda do loop, gesto que começa/termina fora do repouso, movimento robótico (só keys Linear), dois lados fazendo exatamente o mesmo, perna/braço que sai do ritmo do passo do Minecraft e 🆕 **pé escorregando** no chão (com os tempos; veja Chão andando). Vermelho = vai aparecer no jogo, amarelo = provável, azul = dica de estilo. **Ir pra lá** leva até a peça e o tempo. |
| **🆕 Raio-X: quem mexe em cada parte?** | Pra animação selecionada, mostra em cada parte do corpo: soma com o Minecraft, trava o balanço (e se trava rotação, posição ou os dois), desliga o olhar (cabeça para de seguir o mouse) ou tem escala que o jogo ignora. Botões por parte: **Travar aqui (parar o balanço)**, **Devolver pro Minecraft** (apaga as keys ou leva pra um sub-grupo) e **Criar sub-grupo de animação…**. Embaixo, o resumo de todas as animações. |
| **🆕 Criar sub-grupo de animação…** (clique direito numa parte do corpo: cabeça, corpo, braço, perna) | Cria "parte_anim" dentro da parte do corpo, com o mesmo pivô, e leva pra ele as peças e as keys escolhidas. Resolve 3 coisas: a escala passa a funcionar no jogo, o movimento soma com o do Minecraft, e o braço/perna para de "flutuar" quando a animação toca agachado ou nadando (regra do CPM: numa animação não aditiva, girar o membro também trava a posição dele na de pé). |
| **Movimento que some ao trocar de situação…** | Lista peças que se mexem andando/parado mas param ao pular, cair ou agachar. Opções: **Copiar pras situações que faltam**, **Mover pra Global** ou **É de propósito**. |
| **Animações soltas...** | Keyframes de pastas que você apagou ficam guardados. Refez a peça? Reconecte (ele sugere a pasta de mesmo nome), junte ou apague. |
| **Otimizar animação (menos quadros)…** | Deixa o arquivo menor: escolha a tolerância (graus/pixels/escala), veja a tabela antes/depois e aplique. Visibilidade e cor nunca mudam. Também em CPM+ > Otimizar. |
| **Limpar cores fantasmas...** | Apaga keys de cor que não fazem nada no jogo. |
| **Cola de comandos...** | Comandos `/cpmclient animate`, `/cpm animate` e `/cpm detect` prontos, com aspas quando precisa. "Copiar tudo". |

**Importar**
| Item | Como usar |
|---|---|
| **Importar animações de outro .cpmproject…** | Escolha o arquivo; ele mostra quantas peças animadas existem no seu modelo (casa pelo ID do CPM ou pelo caminho de pastas). Marque as animações e importe. |
| **Importar animação Bedrock/GeckoLib...** | Escolha o `.animation.json` e diga qual peça do seu modelo cada osso mexe. Também em Animação na barra do Blockbench. |
| **🆕 Importar emote (Emotecraft / PlayerAnimator)...** | Escolha o `.json` do emote: ele vira um gesto do CPM (botão na roda de gestos) com as suavizações do emote, loop com entrada, o movimento do jogador inteiro distribuído nas partes do corpo e as dobras de cotovelo/joelho (se o modelo tiver "Membros dobráveis"; se não tiver, ele avisa). |

**Propriedades da animação** (clique duplo na animação): ajustes avançados (precisa terminar, esconder da roda, slider) e **Qualidade (fps)** com a contagem exata de quadros.

## 4. 🆕 Modelagem: painel CPM+ Modelar

No modo **Editar**, o painel **CPM+ Modelar** (uma aba ao lado das Texturas) tem todas as ferramentas de modelagem, com o nome escrito em cada botão, em 6 partes: **Criar, Ajustar, Organizar, Textura, Conferir** e **Corpo e rig (CPM)**. Tudo também está em **CPM+ > Modelagem** e no lugar natural de cada coisa dentro do Blockbench:
- **Organizador** (barra de cima da lista de peças): **Formas prontas** e **Biblioteca de peças**, logo depois de "Adicionar grupo".
- **Fileira de ferramentas**: **Medir**, **Construir com blocos** e 🆕 **Encaixe magnético**.
- **Painel de Transformação** (linha da posição): **Alinhar**. Menu **Transformar**: Alinhar e distribuir, Repetir, Copiar atributos.
- Menu **Editar**: Selecionar parecidos ▸, Renomear em lote. Menu **Exibir**: Prévia em 1ª pessoa. Menu **Animação**: Aplicar pose como repouso. **Arquivo > Importar**: Importar .vox.
- **Clique direito** num cubo ou num grupo: a parte "CPM+" com o que serve pra ele (Fatiar, Furar, Achatar, 2ª camada, Cópia ligada, Testar junta, Salvar na biblioteca...).

Regras: **cada ação é um Ctrl+Z**; cópias ganham ids novos do CPM (não brigam no jogo); peças de cor (cubos de cor do CPM) **não gastam espaço da skin**. O peso do modelo aparece só como informação: não existe "limite de 256" travando nada.

### Criar

| Ferramenta | Como usar |
|---|---|
| **Formas prontas...** | Esfera, meia-esfera, cilindro, cone, tubo, anel, arco, cápsula, caixa arredondada e pirâmide feitas de cubos, com prévia ao vivo. No CPM saem como cubos de cor. |
| **Repetir...** | Cópias da peça selecionada em fila, em círculo ou em espiral, com prévia: dentes, espinhos, pétalas, correntes, cercas. |
| **Biblioteca de peças** | Peças prontas, com prévia 3D: clique pra ver, **Colocar** (ou clique duplo). Cada peça vai sozinha pra parte certa do corpo; **Onde** escolhe outra; braço/perna do outro lado sai espelhado. Veja a lista abaixo. |
| **Salvar na biblioteca** | Guarda a peça selecionada (cubos, grupos, cores, brilho, pintura e as animações dela) em **Minhas peças**, pra colocar em qualquer modelo. Dá pra exportar/importar um arquivo de peças pra trocar com os amigos. |
| **Texto em blocos** | Escreve um texto feito de cubos (duas fontes de pixel, com acentos). Tamanho, grossura, espaço, cor, onde fica e pra que lado olha; prévia enquanto você digita. *Mais opções:* linhas, alinhamento, negrito. |
| **Importar .vox (MagicaVoxel)** | Abre um modelo do MagicaVoxel e transforma em cubos de cor (cada cor vira poucos cubos), num grupo novo na parte do corpo que você escolher. Arquivos quebrados são recusados sem mexer no modelo. |
| 🆕 **Converter modelo 3D em peças** | (também em Arquivo > Importar) Um modelo 3D com ossos, de outra aba do Blockbench ou de um arquivo **.bbmodel / .glb / .gltf** (o que se baixa do Sketchfab), vira peças do CPM: **planos pintados em HD** que seguem os ossos, na parte do corpo que você escolher, **com as animações do modelo**. Barra de progresso com Cancelar; um Ctrl+Z desfaz tudo. |
| 🆕 **Malha para cubos** | (também em Arquivo > Importar) As malhas de um modelo 3D (aba, .bbmodel, .glb ou .gltf; não precisa de ossos) viram cubos: corta em cubinhos do tamanho que você escolher, pega as cores da textura e junta no menor número de cubos. Sai como **cubos de cor** (sem gastar skin) ou **cubos pintados**. Contagem e prévia ao vivo. |
| 🆕 **Contorno** | (também no clique direito) Faz um contorno de desenho animado em volta das peças selecionadas: linhas finas e escuras nas bordas da forma. Aparece igual no jogo, de todos os lados, e não gasta espaço da skin. |
| 🆕 **Juntar outro modelo** | (também em Arquivo > Importar) Traz peças de outro modelo pra este: de outra aba do Blockbench ou de um arquivo **.cpmproject / .bbmodel**. Marque as peças na árvore (marcar um grupo traz tudo o que tem dentro; o corpo do jogador do outro modelo nunca vem). Cada peça vai pra parte do corpo de mesmo nome, ou tudo numa parte só, no mesmo lugar. Vem com a pintura (copiada pra um lugar livre da skin, em HD se o outro modelo for HD), cores, brilho, efeitos e as animações dessas peças (com o nome "outro modelo - animação"). Sem espaço na skin: **Mais espaço na textura** ou **Usar só cores**. Um Ctrl+Z desfaz tudo. |
| **2ª camada para peças** | Cria uma casca um pouco maior em volta de cada cubo (como o chapéu e a jaqueta da skin), com um pedaço livre da textura só dela: **com a pintura do cubo** ou **transparente**. |
| **Criar cópia ligada / Desligar cópia** | (clique direito no grupo) A cópia acompanha tudo o que você muda dentro do original: tamanhos, giros, cores, pintura, cubos novos. A cópia só escolhe onde fica e pra onde gira. O vínculo é salvo no .cpmproject e não muda nada no jogo. |

**Biblioteca de peças: o que vem pronto**
- **Cabeça:** Orelhas de gato e de coelho (mexem sozinhas e abaixam quando agacha ou dorme); 🆕 **Orelhas de elfo** em 3 estilos (**realista**, com lóbulo e a borda enrolada; **cartoon/anime**, comprida e pra fora; **low poly**, 2 blocos), que **pegam a cor da pele da sua skin**, mexem uma de cada vez, abaixam ao agachar/dormir e sobem no pulo; Auréola (brilha e gira), Coroa, Cartola, Chapéu de bruxa e os **chifres**: 12 realistas (demônio, diabinho, oni, dragão, unicórnio, carneiro, íbex (sátiro), espiral (kudu), touro (minotauro), órix, galhada de cervo e cristal, que brilha), 🆕 4 **cartoon** (diabinho, carneiro, unicórnio pastel, dragão) e 🆕 3 **low poly** (diabo, carneiro, unicórnio).
- **Rabos:** 6 realistas (raposa, nove caudas (kitsune), gato, lobo, dragão e demônio, que brilha), 🆕 4 **cartoon** (raposa com ponta branca, gato em "?", diabinho com coração, dragão turquesa) e 🆕 3 **low poly** (raposa, gato, dragão). Cada um tem **uma animação pra cada estado do jogador**: parado, andando, correndo, agachado, agachado andando, nadando, caindo, pulando, sentado, dormindo e voando (os cartoon mexem mais rápido e mais solto).
- As peças pintadas usam **pintura feita à mão em pixel art** (luz por face, contorno, sombra de contato) e várias vêm em **HD**: a textura ganha resolução sozinha quando a peça entra.
- **Sem espaço na textura?** A janela oferece **Mais espaço na textura**: a área da textura dobra (64 → 128) sem mexer em nada do que já estava pintado. Fica como um passo próprio do Ctrl+Z.
- 🆕 **Cor da pele do modelo:** peças de pele (as orelhas de elfo) são pintadas na cor da pele da sua skin, lida nas bochechas e nos lados da cabeça, com as luzes e sombras do desenho. Desmarque na janela pra ficar com a cor original da peça.

### Ajustar

| Ferramenta | Como usar |
|---|---|
| **Alinhar e distribuir...** | Alinha as peças selecionadas (por um lado ou pelo meio), espalha com espaço igual ou encosta uma na outra. Funciona com peças giradas. |
| **Copiar atributos...** | Copia tamanho, rotação, pivô, UV, cor e outros ajustes de uma peça pras outras selecionadas, de uma vez. |
| **Medir** | Régua: clique num ponto e depois em outro; mostra a distância em pixels (e em blocos). O ponto gruda no canto mais próximo. O tamanho da seleção aparece no painel. Esc apaga. |
| **Construir com blocos** | Ferramenta da fileira: **clique numa face** pra pôr um bloco de 1 px encostado nela; **arraste uma face** pra puxar ou empurrar (o cubo cresce ou diminui; Ctrl = de ¼ em ¼ px); **Shift + arrastar** = um bloco novo do tamanho que você arrastar. Esc no meio cancela. |
| **Fatiar cubo** | Corta o cubo em X, Y ou Z, num ponto ou em partes iguais. Cada pedaço continua mostrando exatamente a mesma pintura. |
| **Furar cubo** | Abre um furo retangular que atravessa o cubo; o resto vira até 4 peças com a mesma pintura (as paredes do furo usam as laterais do cubo). |
| 🆕 **Encaixe magnético** | (liga/desliga na fileira de ferramentas, logo depois de Construir; a setinha ▾ abre as opções) Enquanto você arrasta um cubo ou grupo pelas setas, a face dele gruda na face, na borda ou no meio dos cubos vizinhos: aparece uma guia rosa e "ímã: nome" junto do mouse. Funciona com peças giradas e, se quiser, ao mudar o tamanho. **Distância** escolhe de quão longe ele puxa. Vem desligado e fica lembrado. |
| 🆕 **Recortar com um cubo** | Selecione o cubo cortador e os cubos a cortar: o espaço do cortador é tirado deles (um furo, um encaixe, um canto). Os pedaços que sobram continuam com a mesma pintura. |

### Organizar

| Ferramenta | Como usar |
|---|---|
| **Juntar cubos** | Junta cubos de cor iguais que se encostam (mesma cor, giro, inflar e brilho, no mesmo grupo) no menor número de cubos possível. O modelo fica igualzinho, mais leve. O **medidor de peso** mostra cubos e tamanho do modelo. |
| **Selecionar parecidos ▸** | Mesmo tamanho, mesma cor, mesmo grupo, a gêmea do outro lado ou o mesmo nome sem o número. **Clicar de novo no mesmo lugar** seleciona a peça de trás; o nome da peça aparece embaixo do mouse. |
| **Renomear em lote...** | Nome + número, antes/depois, trocar texto, trocar esquerda ↔ direita, limpar acentos e espaços. Mostra o antes → depois. |

### Textura

| Ferramenta | Como usar |
|---|---|
| **Mapa da textura** | Mostra quanto da textura está livre (verde), usado (cinza) e usado por mais de uma peça (vermelho). |
| **Textura em HD** | Aumenta a resolução sem mudar o UV: cada pixel vira 2×2, 4×4, 8×8... A skin fica igual e sobra resolução pra detalhes (pincéis HD, penas). |
| **Duplicar com textura própria** | Duplica a peça e dá pra cópia um pedaço livre da textura com a mesma pintura: pintar a cópia não muda a original. |
| 🆕 **Juntar texturas** | (também no menu da textura) O CPM usa uma textura só (a skin). Junta todas as outras texturas do projeto na skin: cada pedaço usado é copiado pixel por pixel pra um lugar livre e os cubos passam a usar a skin. O modelo fica igual. Se faltar lugar, oferece aumentar a skin. |
| 🆕 **Arrumar UV automático** | (também na barra das Texturas e no menu da textura, logo depois de Juntar texturas, e no clique direito do cubo ou grupo) Junta as áreas pintadas das peças selecionadas (ou do modelo todo, sem seleção) na skin, encostadas e sem sobrepor, pixel por pixel: a pintura não muda. Áreas que duas peças dividem continuam divididas, e a skin do jogador (corpo e 2ª camada) nunca sai do lugar. Também separa **pinturas misturadas** sem querer. A janela mostra o antes e o depois e quanto espaço ficou livre; se não couber, oferece **Mais espaço na textura**. Um Ctrl+Z desfaz. |
| **Achatar detalhes na textura** | Cubinhos finos deitados em cima de uma face pintada (olhos, botões, adesivos) viram pintura nessa face e são apagados: menos cubos, mesmo visual. A prévia mostra em verde o que vai e em laranja o que fica (com o motivo). |

### Conferir

| Ferramenta | Como usar |
|---|---|
| **Conferir modelo** | Procura problemas comuns: faces que piscam (z-fighting), frestas finíssimas, pinturas misturadas sem querer, pastas vazias, cubos de tamanho zero, repetidos ou escondidos dentro de outro. Cada linha tem **Mostrar** e **Corrigir**; **Corrigir tudo** de uma vez. Nunca mexe no corpo do jogador. |
| **Conferir simetria do modelo** | Compara esquerda e direita (pelos nomes ou pelo lugar espelhado): par faltando, tamanho, posição, giro, pivô, cor, pintura copiada sem espelhar. **Espelhar deste lado** em cada linha; **Criar o par espelhado** quando falta. |
| **Testar junta** | (clique direito no grupo) Balança o grupo em volta do pivô por alguns segundos, só na tela: uma bolinha amarela marca o pivô e manchas vermelhas aparecem onde abre fresta. Diz se o pivô está certo ou sugere o meio da junta. Não muda nada no modelo. |
| 🆕 **Destacar cubo sob o mouse** | (liga/desliga, também em Exibir) Contorno amarelo no cubo que está embaixo do mouse no modo Editar, pra você saber qual peça vai clicar. Fica lembrado. |
| **Prévia em 1ª pessoa** | (menu Exibir) A câmera vai pros olhos do jogador, com o campo de visão do Minecraft, e o braço aparece no canto como no jogo. Dá pra ver os dois braços. Esc ou o botão Sair volta tudo como estava. |

### Corpo e rig (CPM)

| Ferramenta | Como usar |
|---|---|
| **Bases prontas** | Coloca um corpo pronto: **taur** (corpo de cavalo com 4 patas que andam junto com o jogador), **naga** (cauda de cobra com 11 ossos), **pernas digitígradas**, **pernas altas** ou **baixas** (com joelho, pintadas com a própria skin). As pernas normais são desligadas. Prévia com cartões. |
| **Aplicar pose como repouso** | (modo Animar, menu Animação) A pose que a animação mostra no ponto do tempo vira a pose de repouso dos ossos selecionados, e as keys de todas as animações são compensadas pra continuarem iguais. |
| **Espelhar pro outro lado (CPM)…** | Selecione a peça de um lado (ex.: asa no braço esquerdo): cria a cópia espelhada do outro lado, com nomes, "seguir peça" e slot de item trocados. Pode espelhar as animações junto ou só mover. |
| **Grudar na parte do corpo...** | A peça passa a acompanhar outra parte do corpo no jogo (cabeça, braço...) sem sair do lugar, mesmo com pastas giradas. |
| **Copiar movimento de...** | Editor de Copy Transform: a pasta copia posição, rotação, tamanho, cor ou visibilidade de outra peça. Avisa se a escolha não vai funcionar no jogo. |
| **Fazer camada de armadura...** (clique direito no cubo) | Os cubos passam a mostrar a armadura que o jogador está usando. |
| **Sem animação do Minecraft** | Com uma parte do corpo selecionada: ela para de seguir o balanço do jogo (andar, correr, bater). |
| **Escolher slot de item...** | A pasta vira o lugar onde um item fica (mão, cabeça, ombro). "Nenhum" volta ao normal. |
| **Criar cópia da parte do corpo...** | Cria `body_dup` (ou `head_dup`...) sem animação do Minecraft. Peças soltas selecionadas vão pra cópia sem sair do lugar. |
| **Conferir articulações...** | Avisa quando o pivô da pasta está longe da junta, com conserto de 1 clique. |
| **Montar corrente... / Fazer osso desta peça** | Cauda, pescoço e trança viram ossos com pivô na emenda. |
| **Membros dobráveis (cotovelo, joelho, cintura)...** | Divide braço, perna ou corpo em dois pra parte de baixo dobrar, sem estragar a skin. No clique direito: Criar cotovelo/joelho, Testar dobra, Desfazer. |
| **Curvar peça (saia, poncho, cabelo, cauda)...** | O cubo vira uma corrente de fatias em arco. |

## 5. 🆕 Pintar: painel CPM+ Pintar

Na aba **Pintar**, embaixo do seletor de cores, fica o painel **CPM+ Pintar** (uma aba ao lado do Organizador). Tudo de arte do CPM+ está nele, com o nome escrito em cada botão:
- **Ferramentas:** Sombrear, Esfumar, Esfumar pixel, Pontilhar, Variação e Degradê pixel. Clique e pinte. As opções aparecem embaixo do painel; as menos usadas ficam fechadas em **Mais opções**. O **Esfumar** e o **Esfumar pixel** também estão na fileira de ferramentas do Blockbench, **logo depois da Borracha**.
- **Pincéis:** 45 pincéis em 6 grupos. Clique num grupo pra ver a prévia de cada um, e clique no pincel.
- **Cores e automático:** Criar tons, Trocar cor, Sombra no modelo e Ruído na textura. Abrem uma janela com prévia ao vivo no modelo; o resto também fica em **Mais opções**.

Também dentro do próprio Blockbench: **clique direito numa textura** (Sombra no modelo, Ruído na textura, Trocar cor), a **setinha ao lado do Pincel** do Blockbench (lista dos pincéis do CPM+), os botões **Criar tons** e **Trocar cor** embaixo do seletor de cores, e **Ruído** e **Sombra no modelo** no painel de Texturas. E tudo em **CPM+ > Textura**. Se o painel sumiu: **CPM+ > Textura > Mostrar o painel CPM+ Pintar**. Na barra de cima só entram o Esfumar e o Esfumar pixel (ao lado da Borracha); o resto fica com as coisas do Blockbench.

Regras: cada traço é **um Ctrl+Z**; nada vaza pra outra face do modelo; nunca pinta onde a textura é transparente (exceto os pincéis que pintam por cima de tudo). O **Tamanho** é o mesmo do pincel do Blockbench (também aparece no painel).

| Ferramenta | Como usar |
|---|---|
| **Sombrear** | Passe por cima do que já está pintado. Escolha **Escurecer** ou **Clarear** no painel. A sombra puxa pro azul e a luz pro amarelo, sem precisar escolher cor. *Mais opções:* degrau da rampa (só tons que já existem na textura), matiz/sat/luz, uma vez por traço. |
| **Esfumar** | Arraste em cima da **divisa entre duas cores**: aparecem os tons do meio, usando as cores que já estão ali (não precisa escolher cor). Passar de novo com o pincel maior alarga a mistura. *Mais opções:* arrastar a cor (como um dedo), arrastar pixels, embaralhar pixels, só cores que já existem. |
| **Esfumar pixel** | Igual ao Esfumar, mas pra pixel art: a divisa vira um **xadrez das duas cores**, sem criar cor nova. Passar de novo alarga. |
| **Pontilhar** | Pinta pontinhos alinhados aos pixels com a cor 1. **Densidade** = quantos pixels recebem a cor. *Mais opções:* padrão (xadrez, Bayer, linhas...), buracos com a cor 2, deslocar o padrão. |
| **Variação** | Passe numa área lisa: cada pixel muda um pouquinho (tira o "chapado"). **Cobertura** = quantos pixels mudam. *Mais opções:* tipo e intensidade. |
| **Degradê pixel** | Arraste de onde o degradê começa até onde termina (cor 1 até cor 2), em degraus de pixel art. **Degraus** e **Pontilhado** no painel. *Mais opções:* cor 1 até transparente, rampa salva, radial. O botão Degradê do próprio Blockbench continua fazendo o degradê normal. |
| **Criar tons** | Gera os tons de sombra e luz de uma cor (ou entre duas cores). Clique num tom pra usar como cor 1. **Pôr na paleta** ou **Salvar em Minhas rampas** (o Sombrear e o Degradê pixel usam). |
| **Trocar cor** | Clique numa família de cores (ex.: todos os vermelhos da roupa) e mude o **Matiz**: todos os tons mudam juntos e o sombreado fica igual. **Virar a cor 1** leva a família pra cor selecionada. |
| **Sombra no modelo** | Luz e sombra calculadas pela forma do modelo: escolha **de onde vem a luz** e confirme. Funciona nas peças selecionadas ou no modelo todo; depois é só retocar à mão. |
| **Ruído na textura** | A Variação de uma vez: na textura inteira, na seleção ou nas peças selecionadas. |

### 🆕 Pincéis (45)

Pincéis no estilo dos mais usados pela comunidade dos apps de desenho (os mais instalados da galeria do ibisPaint), refeitos do zero. Ficam na parte **Pincéis** do painel CPM+ Pintar. Cada pincel tem dois jeitos:
- **HD**: pontas desenhadas, bordas lisas, textura e mistura de cor, como num app de desenho (lápis com grão, render que mistura com a cor de baixo, óleo com cerdas, fita de fogo com brilho, nuvens, penas...). Pra texturas de 128 px ou mais.
- **Pixel art**: carimbos desenhados pixel a pixel, sem cor nova. Pra skin 64×64.

O CPM+ escolhe sozinho pelo tamanho da textura, e a prévia de cada pincel já mostra o jeito que vai sair. Pra trocar na mão: **Mais opções > Estilo** (Automático, HD, Pixel art).

- **Cor 1 e cor 2:** o clique esquerdo pinta com a cor 1, e a cor 2 entra nos detalhes (miolo da flor, contorno cor 2, xadrez, pontos da cicatriz). O clique direito troca as duas.
- O **Tamanho** muda o tamanho dos carimbos, fios e padrões. Nos carimbos, *Mais opções* tem **Espalhar** e **Variação** (0 a 200%).
- Passar de novo no mesmo lugar, no mesmo traço, **não acumula**. Os padrões (xadrez, listras, escamas, jeans, tricô...) ficam **presos à textura**: pinte em vários traços que o padrão continua alinhado.

| Grupo | Pincéis |
|---|---|
| **Desenho e pintura** | **Lápis com grão** (grafite que afina nas pontas, grão na borda), **Lineart** (afina no começo e no fim; em 1px faz linha pixel-perfect), **Contorno auto** (cor + contorno escuro), **Contorno cor 2**, **Renderizar** (cobre com borda macia e mistura um pouco com a cor de baixo), **Mesclar suave** (aerógrafo leve: passe na divisa de duas cores pra mesclar; em 64×64 é pontilhado, sem cor nova), **Aquarela**, **Óleo / guache** (cerdas, arrasta a cor de baixo), **Arco-íris**, **Pixel 1px** (atalho: o Pincel do Blockbench em 1px com pixel-perfect) |
| **Cabelo, pelo e escamas** | **Cabelo (fios)**, **Cachos**, **Brilho de cabelo** (só clareia o que já está pintado), **Pelo**, **Penas**, **Escamas** |
| **Natureza e efeitos** | **Grama**, **Folhagem** (tufos de borda serrilhada em cor chapada, no estilo do pincel de grama mais instalado da comunidade: grama, arbusto, copa de árvore), **Folhas**, **Flores**, **Nuvens**, **Fogo**, **Respingos** (sangue, lama, tinta), **Bolhas** |
| **Brilho, glitter e rosto** | **Glitter**, **Brilhos**, **Estrelas**, **Neon**, **Blush**, **Olhos (íris)** |
| **Enfeites** | **Corações**, **Renda**, **Pérolas**, **Corrente**, **Costura**, **Cicatriz** |
| **Padrões e texturas** | **Xadrez**, **Listras**, **Bolinhas**, **Xadrez escocês**, **Jeans**, **Tricô**, **Papel**, **Grão**, **Retícula** |

Dicas: **Fogo** fica melhor com laranja como cor 1 (em HD é uma fita de fogo com miolo claro e brilho, que fica ainda melhor em fundo escuro). **Nuvens**: pinte com 2 ou 3 tons pra dar volume. **Folhagem**: é de uma cor só, como o original; pinte primeiro o verde escuro e depois um verde mais claro por cima, mais pro alto, pra dar volume. Dá pra clicar (um tufo) ou arrastar (uma faixa). **Brilho de cabelo**, **Papel** e **Grão** vão por cima de uma cor já pintada. **Olhos**: um clique em cada olho. **Renda**, **Pérolas** e **Corrente** ficam mais bonitas em traço reto (segure Shift pra fazer linha reta).

## 6. Textura e otimização

| Item | Onde | Como usar |
|---|---|---|
| **Pintar: pele de baixo da 2ª camada** (e Mostrar tudo / Só a 2ª camada / Só o selecionado / Alternar) | CPM+ > Prévia | O pincel atravessa a camada transparente pra pintar a pele de baixo. |
| **Conferir simetria (pintura espelhada)** | CPM+ > Textura | Mostra peças em que o pincel espelhado não pinta e ajuda a alinhar. |
| **Achar peças de cor única** / **Virar cor sólida…** | CPM+ > Otimizar / clique direito no cubo | Peça de uma cor só vira cubo de cor (menos textura). |

## 7. Prévia (CPM+ > Prévia)

| Item | Como usar |
|---|---|
| **Girar um eixo por vez** | Ligado: girar no X não mexe Y e Z no gizmo. |
| **Ver esqueleto** | Desenha ossos, juntas e raízes do CPM por cima do modelo. |
| **Item na mão (prévia do editor do CPM)** / **Item na mão...** | Mostra um item na mão pra conferir poses; escolha o item. Só visual, não vai no arquivo. |
| **🆕 Rastro das pontas, Prévia em degrau, Silhueta, Clicar de novo pega a peça de trás** | Veja a seção 3, "Ver melhor". |

## 8. Ajuda (CPM+ > Ajuda)

- **Limpar armazenamento do CPM...**: mostra quanto espaço o editor do CPM usa dentro do Blockbench e apaga autosaves antigos.

---

## 9. Avisos automáticos no export (Arquivo > Exportar .cpmproject)

O plugin confere o modelo antes de exportar e mostra uma lista. Cada linha tem **Mostrar** (acha a peça) e, quando dá, um botão de conserto.

| Aviso | O que significa | Conserto |
|---|---|---|
| **Limites do jogo** | Cubo maior que 25,5 px ou posição mais longe que 48 px (inclusive em animação). O jogo corta sem avisar; a linha mostra o valor que ele vai usar. | Divida o cubo / aproxime o pivô. |
| **Animação grande demais** | Mais de 255 quadros, 32,7 s ou 255 peças: toca só o começo, quebra ou mexe a peça errada. | **Ajustar** (baixa o fps ou abre o Otimizar). |
| **Parte do corpo fantasma** | Apagou/desligou a pasta de uma parte do corpo e o jogo mostra a parte normal do Minecraft. | **Esconder a parte normal**, **Quero a parte normal** ou **Ligar a exportação**. |
| **Peças soltas** | Cubos fora de qualquer pasta vão pro Corpo no jogo. | **Resolver…**: Grudar onde estão / Mover para o Corpo. |
| **Animações soltas** | Keyframes de peças apagadas não vão pro jogo. | **Reconectar…** |
| **Raiz desconhecida** | Pasta no topo com nome que o CPM não conhece. | Escolher a parte do corpo (já vem sugerida pela posição). |
| **Falta o ID do modelo** | Opções "Salvar entre partidas" não serão lembradas. | **Criar ID**. |
| **Botão renomeado** | Quem usa o modelo perde o atalho/opção salva. | **Voltar ao nome antigo** ou **Está certo**. |
| **Movimento que some** | Peça que para ao pular/cair/agachar (só informa). | Abre a ferramenta. |
| **🆕 Troca de pose com pulo** | Peça que dá um pulo grande quando o jogo troca de pose (parado → andando...). Só informa. | Abre o Relatório de estalos. |
| **🆕 Cabeça para de seguir o mouse** | Animação não aditiva com keys na cabeça desliga o olhar. Só informa (às vezes é de propósito). | Abre o Raio-X (Devolver pro Minecraft). |
| **Fórmulas e camadas da skin** | Animação com fórmula Molang ou sem espaço nas camadas da skin. | Transformar em keyframes / escolher camadas. |

## 10. Correções automáticas (não precisa fazer nada)

- Animações saem iguais no jogo (sem quadros extras, suave continua suave, toggle de 1 quadro, fórmulas).
- Modelos pesados abrem, salvam e exportam bem mais rápido, com barra de progresso.
- Grupos não ficam escondidos depois do modo Animar.
- "Copiar cor" do Copy Transform não se perde.
- Ctrl+Z de "Adicionar slots/partes" e dos consertos desfaz tudo de uma vez.
- Apagar uma pasta animada guarda as keys (veja Animações soltas).

---
Código-fonte (MIT): https://github.com/solismissus-bot/CPM-Plugin-Plus · Novidades de cada versão: COMO_TESTAR.md
